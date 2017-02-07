(ns oc.auth.api.users
  "Liberator API for user resources."
  (:require [clojure.string :as s]
            [if-let.core :refer (if-let* when-let*)]
            [taoensso.timbre :as timbre]
            [compojure.core :as compojure :refer (defroutes OPTIONS GET PATCH POST DELETE)]
            [liberator.core :refer (defresource by-method)]
            [liberator.representation :refer (ring-response)]
            [schema.core :as schema]
            [oc.lib.schema :as lib-schema]
            [oc.lib.db.pool :as pool]
            [oc.lib.jwt :as jwt]
            [oc.lib.api.common :as api-common]
            [oc.auth.config :as config]
            [oc.auth.api.slack :as slack-api]
            [oc.auth.resources.team :as team-res]
            [oc.auth.resources.user :as user-res]
            [oc.auth.representations.media-types :as mt]
            [oc.auth.representations.user :as user-rep]))

;; ----- Actions -----

(defn- create-user [conn {email :email password :password :as user-props}]
  (timbre/info "Creating user" email)
  (if-let* [created-user (user-res/create-user! conn (user-res/->user user-props password))
            admin-teams (user-res/admin-of conn (:user-id created-user))]
    (do (timbre/info "Created user" email)
      {:new-user (assoc created-user :admin admin-teams)})
    (do (timbre/error "Failed creating user" email)
      false)))

(defn- update-user [conn {data :data} user-id]
  (if-let [updated-user (user-res/update-user! conn user-id data)]
    {:updated-user updated-user}
    false))

;; ----- Validations -----

(defn token-auth [conn headers]
  (if-let* [authorization (or (get headers "Authorization") (get headers "authorization"))
            token (last (s/split authorization #" "))]
    
    (if-let [user (and (lib-schema/valid? lib-schema/UUIDStr token) ; it's a valid UUID
                       (user-res/get-user-by-token conn token))] ; and a user has it as their token
      (let [user-id (:user-id user)]
        (timbre/info "Auth'd user" user-id "by token" token)
        (user-res/update-user! conn user-id (assoc user :status :active)) ; mark user active
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
              teams (team-res/get-teams conn (:teams accessed-user) [:admins])]
      (some #((set (:admins %)) accessing-user-id) teams)
      false)))

(defn- processable-patch-req? [conn {data :data} user-id]
  (if-let [user (user-res/get-user conn user-id)]
    (try
      (schema/validate user-res/User (merge user (user-res/ignore-props data)))
      true
      (catch clojure.lang.ExceptionInfo e
        (timbre/error e "Validation failure of user PATCH request.")
        false))
    true)) ; No user for this user-id, so this will 404 after :exists? decision

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

;; A resource for authenticating users by email/pass
(defresource user-auth [conn]

  :allowed-methods [:options :get]

  :available-media-types [jwt/media-type]
  :handle-not-acceptable (api-common/only-accept 406 jwt/media-type)

  :authorized? (by-method {:options true
                           :get (fn [ctx] (or (-> ctx :request :identity) ; Basic HTTP Auth
                                              (token-auth conn (-> ctx :request :headers))))}) ; one time use token auth

  :handle-ok (fn [ctx] (when-let* [user (user-res/get-user-by-email conn (or
                                                                            (-> ctx :request :identity) ; Basic HTTP Auth
                                                                            (:email ctx))) ; one time use token auth
                                   admin-teams (user-res/admin-of conn (:user-id user))]
                        (user-rep/auth-response (assoc user :admin admin-teams) :email)))) ; respond w/ JWToken and location

;; A resource for creating users by email
(defresource user-create [conn]
  (api-common/open-company-anonymous-resource config/passphrase) ; verify validity and presence of required JWToken

    :allowed-methods [:options :post]

    ;; Media type client accepts
    :available-media-types [jwt/media-type]
    :handle-not-acceptable (api-common/only-accept 406 jwt/media-type)

    ;; Media type client sends
    :known-content-type? (by-method {
      :options true
      :get (fn [ctx] (api-common/known-content-type? ctx mt/user-media-type))})

    :exists? (fn [ctx] {:existing-user (user-res/get-user-by-email conn (-> ctx :data :email))})

    ;; Validations
    :processable? (by-method {
      :options true
      :post (fn [ctx] (and (lib-schema/valid-email-address? (-> ctx :data :email))
                           (lib-schema/valid-password? (-> ctx :data :password))
                           (string? (-> ctx :data :first-name))
                           (string? (-> ctx :data :last-name))))})

    ;; Actions
    :post-to-existing? false
    :put-to-existing? true ; needed for a 409 conflict
    :conflict? :existing-user
    :put! (fn [ctx] (create-user conn (:data ctx))) ; POST ends up handled here so we can have a 409 conflict

    ;; Responses
    :handle-conflict (ring-response {:status 409})
    :handle-created (fn [ctx] (user-rep/auth-response (:new-user ctx) :email))) ; respond w/ JWToken and location

;; A resource for operations on a particular user
(defresource user [conn user-id]
  (api-common/open-company-authenticated-resource config/passphrase) ; verify validity and presence of required JWToken

  :allowed-methods [:options :get :patch :delete]

  :available-media-types [mt/user-media-type]
  :handle-not-acceptable (api-common/only-accept 406 mt/user-media-type)
  
  :known-content-type? (by-method {
                          :options true
                          :get true
                          :patch (fn [ctx] (api-common/known-content-type? ctx mt/user-media-type))
                          :delete true})

  :allowed? (fn [ctx] (allow-user-and-team-admins conn (:user ctx) user-id))

  :processable? (by-method {
    :get true
    :options true
    :patch (fn [ctx] (processable-patch-req? conn ctx user-id))
    :delete true})

  :exists? (fn [ctx] (if-let [user (and (lib-schema/unique-id? user-id) (user-res/get-user conn user-id))]
                        {:existing-user user}
                        false))

  :patch! (fn [ctx] (update-user conn ctx user-id))
  :delete! (fn [_] (user-res/delete-user! conn user-id))

  :handle-ok (by-method {
    :get (fn [ctx] (user-rep/render-user (:existing-user ctx)))
    :patch (fn [ctx] (user-rep/render-user (:updated-user ctx)))}))

;; A resource for refreshing JWTokens
(defresource token [conn user-id]

  ;; Get the JWToken and ensure it checks, but don't check if it's valid (might be expired or old schema, and that's OK)
  :initialize-context (fn [ctx] (let [token (api-common/get-token (get-in ctx [:request :headers]))]
                                  (when (jwt/check-token token config/passphrase)
                                      {:jwtoken token :user (:claims (jwt/decode token))})))
  
  :allowed-methods [:options :get]

  :available-media-types [jwt/media-type]
  :handle-not-acceptable (api-common/only-accept 406 jwt/media-type)

  :allowed? (by-method {
      :options true
      :get (fn [ctx] (= (-> ctx :user :user-id) user-id))}) ; refreshing their own JWToken

  :exists? (fn [ctx] (if-let* [user (and (lib-schema/unique-id? user-id) (user-res/get-user conn user-id))
                               admin-teams (user-res/admin-of conn (:user-id user))]
                      {:existing-user (assoc user :admin admin-teams)}
                      false))

  :handle-ok (fn [ctx] (case (-> ctx :user :auth-source)

                        ;; Email token - respond w/ JWToken and location
                        "email" (user-rep/auth-response (:existing-user ctx) :email)

                        ;; Slack token - defer to Slack API handler
                        "slack" (slack-api/refresh-token conn (:existing-user ctx)
                                                              (-> ctx :user :slack-id)
                                                              (-> ctx :user :slack-token))
        
                        ;; What token is this?
                        (api-common/unauthorized-response))))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
      ;; new email user creation
      (OPTIONS "/users" [] (pool/with-pool [conn db-pool] (user-create conn)))
      (OPTIONS "/users/" [] (pool/with-pool [conn db-pool] (user-create conn)))
      (POST "/users" [] (pool/with-pool [conn db-pool] (user-create conn)))
      (POST "/users/" [] (pool/with-pool [conn db-pool] (user-create conn)))
      ;; email / token user authentication
      (OPTIONS "/users/auth" [] (pool/with-pool [conn db-pool] (user-auth conn)))
      (OPTIONS "/users/auth/" [] (pool/with-pool [conn db-pool] (user-auth conn)))
      (GET "/users/auth" [] (pool/with-pool [conn db-pool] (user-auth conn)))
      (GET "/users/auth/" [] (pool/with-pool [conn db-pool] (user-auth conn)))
      ;; password reset request
      ; (OPTIONS "/users/reset" [] (pool/with-pool [conn db-pool] (password-reset conn)))
      ; (OPTIONS "/users/reset/" [] (pool/with-pool [conn db-pool] (password-reset conn)))
      ; (POST "/users/reset" [] (pool/with-pool [conn db-pool] (password-reset conn)))
      ; (POST "/users/reset/" [] (pool/with-pool [conn db-pool] (password-reset conn))))
      ;; user operations
      (OPTIONS "/users/:user-id" [user-id] (pool/with-pool [conn db-pool] (user conn user-id)))
      (GET "/users/:user-id" [user-id] (pool/with-pool [conn db-pool] (user conn user-id)))
      (PATCH "/users/:user-id" [user-id] (pool/with-pool [conn db-pool] (user conn user-id)))
      (DELETE "/users/:user-id" [user-id] (pool/with-pool [conn db-pool] (user conn user-id)))
      ;; token refresh request
      (OPTIONS "/users/:user-id/refresh-token" [user-id] (pool/with-pool [conn db-pool] (token conn user-id)))
      (OPTIONS "/users/:user-id/refresh-token/" [user-id] (pool/with-pool [conn db-pool] (token conn user-id)))
      (GET "/users/:user-id/refresh-token" [user-id] (pool/with-pool [conn db-pool] (token conn user-id)))
      (GET "/users/:user-id/refresh-token/" [user-id] (pool/with-pool [conn db-pool] (token conn user-id))))))