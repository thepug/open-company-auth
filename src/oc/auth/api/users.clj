(ns oc.auth.api.users
  "Liberator API for user resources."
  (:require [clojure.string :as s]
            [if-let.core :refer (if-let* when-let*)]
            [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes ANY)]
            [liberator.core :refer (defresource by-method)]
            [liberator.representation :refer (ring-response)]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.lib.db.pool :as pool]
            [oc.lib.jwt :as jwt]
            [oc.lib.api.common :as api-common]
            [oc.auth.config :as config]
            [oc.auth.lib.sqs :as sqs]
            [oc.auth.api.slack :as slack-api]
            [oc.auth.resources.team :as team-res]
            [oc.auth.resources.user :as user-res]
            [oc.auth.representations.media-types :as mt]
            [oc.auth.representations.user :as user-rep]))

;; ----- Validations -----

(defn token-auth [conn headers]
  (if-let* [authorization (or (get headers "Authorization") (get headers "authorization"))
            token (last (s/split authorization #" "))]
    
    (if-let [user (and (lib-schema/valid? lib-schema/UUIDStr token) ; it's a valid UUID
                       (user-res/get-user-by-token conn token))] ; and a user has it as their token
      (let [user-id (:user-id user)]
        (timbre/info "Auth'd user:" user-id "by token:" token)
        (user-res/activate! conn user-id) ; mark user active
        (user-res/remove-token conn user-id) ; remove the used token
        {:email (:email user)})
    
      false) ; token is not a UUID, or no user for the token
    
    false)) ; no Authorization header or no token in the header

(defn email-basic-auth
  "HTTP Basic Auth function (email/pass) for ring middleware."
  [sys req auth-data]
  (when-let* [email (:username auth-data)
              password (:password auth-data)]
    (pool/with-pool [conn (-> sys :db-pool :pool)] 
      (if (user-res/authenticate? conn email password)
        (do 
          (timbre/info "Authed:" email)
          email)
        (do
          (timbre/info "Failed to auth:" email) 
          false)))))

(defn- allow-user-and-team-admins [conn {accessing-user-id :user-id} accessed-user-id]
  (or
    ;; JWToken user-id matches URL user-id, user accessing themself
    (= accessing-user-id accessed-user-id)

    ;; check if the accessing user is an admin of any of the accessed user's teams
    (if-let* [accessed-user (user-res/get-user conn accessed-user-id)
              teams (team-res/list-teams-by-ids conn (:teams accessed-user) [:admins])]
      (some #((set (:admins %)) accessing-user-id) teams)
      false)))

(defn- valid-user-update? [conn user-props user-id]
  (if-let [user (user-res/get-user conn user-id)]
    (let [current-password (:current-password user-props)
          new-password (:password user-props)
          updated-user (merge user (user-res/ignore-props (dissoc user-props :current-password)))]
      (if (and (lib-schema/valid? user-res/User updated-user)
               (or (nil? new-password) ; not attempting to change password
                   (and (empty? current-password) (not (nil? new-password))) ; attempting to set a new password but with no old password
                   (and (seq current-password) (user-res/password-match? current-password (:password-hash user))))) ; attempting to change the password with an old password set, checking that the old password match
        {:existing-user user :user-update (if new-password (assoc updated-user :password new-password) user-props)}
        [false, {:user-update updated-user}])) ; invalid update
    true)) ; No user for this user-id, so this will fail existence check later

(defn malformed-email?
  "Read in the body param from the request and make sure it's a non-blank string
  that corresponds to an email address. Otherwise just indicate it's malformed."
  [ctx]
  (try
    (if-let* [email (slurp (get-in ctx [:request :body]))
              valid? (lib-schema/valid-email-address? email)]
      [false {:data email}]
      true)
    (catch Exception e
      (do (timbre/warn "Request body not processable as an email address: " e)
        true))))

;; ----- Actions -----

(defn- create-user [conn {email :email password :password :as user-props}]
  (timbre/info "Creating user:" email)
  (if-let* [created-user (user-res/create-user! conn (user-res/->user user-props password))
            user-id (:user-id created-user)
            admin-teams (user-res/admin-of conn user-id)]
    (do
      (timbre/info "Created user:" email)
      (timbre/info "Sending email verification request for:" user-id "(" email ")")
      (sqs/send! sqs/TokenAuth
                 (sqs/->token-auth {:type :verify :email email :token (:one-time-token created-user)})
                 config/aws-sqs-email-queue)
      (timbre/info "Sent email verification for:" user-id "(" email ")")
      {:new-user (assoc created-user :admin admin-teams)})
    
    (do
      (timbre/error "Failed creating user:" email) false)))

(defn- update-user [conn ctx user-id]
  (timbre/info "Updating user:" user-id)
  (if-let* [updated-user (:user-update ctx)
            update-result (user-res/update-user! conn user-id updated-user)]
    (do
      (timbre/info "Updated user:" user-id)
      {:updated-user update-result})

    (do (timbre/error "Failed updating user:" user-id) false)))

(defn- delete-user [conn user-id]
  (timbre/info "Deleting user:" user-id)
  (if (user-res/delete-user! conn user-id)
    (do (timbre/info "Deleted user:" user-id) true)
    (do (timbre/error "Failed deleting user:" user-id) false)))

(defn password-reset-request [conn email]
  (timbre/info "Password reset request for:" email)
  (if-let [user (user-res/get-user-by-email conn email)]

    (let [user-id (:user-id user)
          one-time-token (str (java.util.UUID/randomUUID))]
      (timbre/info "Adding one-time-token for:" user-id "(" email ")")
      (user-res/update-user! conn user-id {:one-time-token one-time-token})
      (timbre/info "Sending password reset request for:" user-id "(" email ")")
      (sqs/send! sqs/TokenAuth
                 (sqs/->token-auth {:type :reset :email email :token one-time-token})
                 config/aws-sqs-email-queue)
      (timbre/info "Sent password reset request for:" user-id "(" email ")"))

    (timbre/warn "Password reset request, no user for:" email)))

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

;; A resource for authenticating users by email/pass
(defresource user-auth [conn]

  :allowed-methods [:options :get]

  ;; Media type client accepts
  :available-media-types [jwt/media-type]
  :handle-not-acceptable (api-common/only-accept 406 jwt/media-type)

  ;; Authorization
  :authorized? (by-method {
    :options true
    :get (fn [ctx] (or (-> ctx :request :identity) ; Basic HTTP Auth
                       (token-auth conn (-> ctx :request :headers))))}) ; one time use token auth

  ;; Responses
  :handle-ok (fn [ctx] (when-let* [user (user-res/get-user-by-email conn (or
                                                                            (-> ctx :request :identity) ; Basic HTTP Auth
                                                                            (:email ctx))) ; one time use token auth
                                   admin-teams (user-res/admin-of conn (:user-id user))]
                        (if (= (:status user) "pending")
                          ;; they need to verify their email, so no love
                          (api-common/blank-response)
                          ;; respond w/ JWToken and location
                          (user-rep/auth-response 
                            (-> user
                              (assoc :admin admin-teams)
                              (assoc :slack-bots (slack-api/bots-for conn user)))
                            :email)))))

;; A resource for creating users by email
(defresource user-create [conn]
  (api-common/open-company-anonymous-resource config/passphrase) ; verify validity of JWToken if it's provided

  :allowed-methods [:options :post]

  ;; Media type client accepts
  :available-media-types [jwt/media-type]
  :handle-not-acceptable (api-common/only-accept 406 jwt/media-type)

  ;; Media type client sends
  :known-content-type? (by-method {
    :options true
    :post (fn [ctx] (api-common/known-content-type? ctx mt/user-media-type))})

  ;; Validations
  :processable? (by-method {
    :options true
    :post (fn [ctx] (and (lib-schema/valid-email-address? (-> ctx :data :email))
                         (lib-schema/valid-password? (-> ctx :data :password))
                         (string? (-> ctx :data :first-name))
                         (string? (-> ctx :data :last-name))))})

  ;; Existentialism
  :exists? (fn [ctx] {:existing-user (user-res/get-user-by-email conn (-> ctx :data :email))})

  ;; Actions
  :post-to-existing? false
  :put-to-existing? true ; needed for a 409 conflict
  :conflict? :existing-user
  :put! (fn [ctx] (create-user conn (:data ctx))) ; POST ends up handled here so we can have a 409 conflict

  ;; Responses
  :handle-conflict (ring-response {:status 409})
  :handle-created (fn [ctx] (let [user (:new-user ctx)]
                              (if (= (:status user) "pending")
                                ;; they need to verify their email, so no love
                                (api-common/blank-response)
                                ;; respond w/ JWToken and location
                                (user-rep/auth-response
                                  (assoc user :slack-bots (slack-api/bots-for conn user))
                                  :email)))))


;; A resource for operations on a particular user
(defresource user [conn user-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get :patch :delete]

  ;; Media type client accepts
  :available-media-types [mt/user-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/user-media-type)
  
  ;; Media type client sends
  :known-content-type? (by-method {
                          :options true
                          :get true
                          :patch (fn [ctx] (api-common/known-content-type? ctx mt/user-media-type))
                          :delete true})

  ;; Authorization
  :allowed? (by-method {
    :options true
    :get (fn [ctx] (allow-user-and-team-admins conn (:user ctx) user-id))
    :patch (fn [ctx] (allow-user-and-team-admins conn (:user ctx) user-id))
    :delete (fn [ctx] (allow-user-and-team-admins conn (:user ctx) user-id))})

  ;; Validations
  :processable? (by-method {
    :get true
    :options true
    :patch (fn [ctx] (valid-user-update? conn (:data ctx) user-id))
    :delete true})

  ;; Existentialism
  :exists? (fn [ctx] (if-let [user (and (lib-schema/unique-id? user-id) (user-res/get-user conn user-id))]
                        {:existing-user user}
                        false))

  ;; Acctions
  :patch! (fn [ctx] (update-user conn ctx user-id))
  :delete! (fn [_] (delete-user conn user-id))

  ;; Responses
  :handle-ok (by-method {
    :get (fn [ctx] (user-rep/render-user (:existing-user ctx)))
    :patch (fn [ctx] (user-rep/render-user (:updated-user ctx)))})
  :handle-unprocessable-entity (fn [ctx]
    (api-common/unprocessable-entity-response (schema/check user-res/User (:user-update ctx)))))

;; A resource for refreshing JWTokens
(defresource token [conn]

  ;; Get the JWToken and ensure it checks, but don't check if it's expired (might be expired or old schema, and that's OK)
  :initialize-context (by-method {
    :get (fn [ctx] (if-let* [token (api-common/get-token (get-in ctx [:request :headers]))
                             claims (:claims (jwt/decode token))]
                      (when (and (jwt/check-token token config/passphrase) ; we signed it
                            (nil? (schema/check jwt/Claims claims))) ; claims are valid
                        {:jwtoken token :user claims})))})
  
  :allowed-methods [:options :get]

  ;; Media type client accepts
  :available-media-types [jwt/media-type]
  :handle-not-acceptable (api-common/only-accept 406 jwt/media-type)

  ;; Existentialism
  :exists? (fn [ctx] (if-let* [user-id (-> ctx :user :user-id)
                               user (user-res/get-user conn user-id)
                               admin-teams (user-res/admin-of conn user-id)]
                        {:existing-user (assoc user :admin admin-teams)}
                        false))

  ;; Responses
  :handle-not-found (api-common/unauthorized-response)
  :handle-ok (fn [ctx] (case (-> ctx :user :auth-source)

                        ;; Email token - respond w/ JWToken and location
                        "email" (let [user (:existing-user ctx)]
                                  (user-rep/auth-response  
                                    (assoc user :slack-bots (slack-api/bots-for conn user))
                                    :email))

                        ;; Slack token - defer to Slack API handler
                        "slack" (slack-api/refresh-token conn (:existing-user ctx)
                                                              (-> ctx :user :slack-id)
                                                              (-> ctx :user :slack-token))
        
                        ;; What token is this?
                        (api-common/unauthorized-response))))

;; A resource for requesting a password reset
(defresource password-reset [conn]
  (api-common/open-company-anonymous-resource config/passphrase) ; verify validity of JWToken if it's provided

  :allowed-methods [:options :post]

  ;; Media type client accepts
  :available-media-types ["*/*"]
  
  ;; Media type client sends
  :malformed? (by-method {
    :options false
    :post (fn [ctx] (malformed-email? ctx))
    :delete false})  
  :known-content-type? (by-method {
    :options true
    :post (fn [ctx] (api-common/known-content-type? ctx "text/x-email"))})

  ;; Actions
  :post! (fn [ctx] (password-reset-request conn (:data ctx)))

  ;; Responses
  :handle-created (api-common/blank-response))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      ;; new email user creation
      (ANY "/users" [] (pool/with-pool [conn db-pool] (user-create conn)))
      (ANY "/users/" [] (pool/with-pool [conn db-pool] (user-create conn)))
      ;; email / token user authentication
      (ANY "/users/auth" [] (pool/with-pool [conn db-pool] (user-auth conn)))
      (ANY "/users/auth/" [] (pool/with-pool [conn db-pool] (user-auth conn)))
      ;; password reset request
      (ANY "/users/reset" [] (pool/with-pool [conn db-pool] (password-reset conn)))
      (ANY "/users/reset/" [] (pool/with-pool [conn db-pool] (password-reset conn)))
      ;; token refresh request
      (ANY "/users/refresh" [] (pool/with-pool [conn db-pool] (token conn)))
      (ANY "/users/refresh/" [] (pool/with-pool [conn db-pool] (token conn)))
      ;; user operations
      (ANY "/users/:user-id" [user-id] (pool/with-pool [conn db-pool] (user conn user-id))))))