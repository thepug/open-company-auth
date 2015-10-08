(ns open-company-auth.app
  (:require [compojure.core :refer :all]
            [ring.util.response :refer [redirect]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.cors :refer (wrap-cors)]
            [raven-clj.ring :refer (wrap-sentry)]
            [org.httpkit.server :refer (run-server)]
            [clojure.data.json :as json]
            [open-company-auth.config :as config]
            [clj-slack.oauth :as slack-oauth]
            [clj-slack.auth :as slack-auth]
            [clj-slack.users :as slack-users]
            [open-company-auth.jwt :as jwt]))

(def ^:private slack-endpoint "https://slack.com/api")
(def ^:private slack-connection {:api-url slack-endpoint})
(def ^:private json-mime-type {"Content-Type" "application/json"})
(def ^:private html-mime-type {"Content-Type" "text/html"})

(def ^:private slack {
  :redirectURI  "/slack-oauth"
  :state        "open-company-auth"
  :scope        "identify,read,post"})

(defn format-response
  "Format a generic response helper"
  [body headers status & [other-options]]
  (merge
    {:body (json/write-str body)
     :headers headers
     :status status}
    other-options))

(defn error-response
  "Return a formatted ring response with an error and :ok false"
  [error]
  (format-response {:ok false :error error} json-mime-type 200))

(defroutes auth-routes
  (GET "/" []
       {:body "it works!"
        :headers html-mime-type
        :status 200})
  (GET "/auth-settings" []
    (let [url (str "https://slack.com/oauth/authorize?client_id="
                   config/slack-client-id
                   "&redirect_uri="
                   config/server-name (:redirectURI slack)
                   "&state="
                   (:state slack)
                   "&scope="
                   (:scope slack))
          settings (merge {:full-url url} slack)]
      {:body (json/write-str settings)
       :headers json-mime-type
       :status 200}))
  (GET "/slack-oauth" {params :params}
    (if (contains? params "test")
      ; for testing purpose
      (format-response {:test true :ok true} json-mime-type 200)
      ; normal execution
      (let [parsed-body (slack-oauth/access slack-connection
                                            config/slack-client-id
                                            config/slack-client-secret
                                            (params "code")
                                            (str config/server-name (:redirectURI slack)))
            access-ok (:ok parsed-body)]
        (if-not access-ok
          (error-response "invalid slack code")
          (let [access-token (:access_token parsed-body)
                parsed-test-body (slack-auth/test (merge slack-connection {:token access-token}))
                test-ok (:ok parsed-body)]
            (if-not test-ok
              (error-response "error in test call")
              (let [user-id (:user_id parsed-test-body)
                    user-info-parsed (slack-users/info (merge slack-connection {:token access-token}) user-id)
                    info-ok (:ok user-info-parsed)]
                (if-not info-ok
                  (error-response "error in info call")
                  (let [user-obj (:user user-info-parsed)
                        profile-obj (:profile user-obj)
                        jwt-content {:user_id user-id
                                     :name (:name user-obj)
                                     :team (:team parsed-test-body)
                                     :team_id (:team_id parsed-test-body)
                                     :real_name (:real_name profile-obj)
                                     :image_192 (:image_192 profile-obj)
                                     :email (:email profile-obj)}
                        jwt (jwt/generate jwt-content)]
                    (redirect (str config/web-server-name "/login?jwt=" jwt)))))))))))
  (GET "/test-token" []
    (let [payload {:test "test" :bago "bago"}
          jwt-token (jwt/generate payload)
          jwt-verified (jwt/check-token jwt-token)
          jwt-decoded (jwt/decode jwt-token)
          resp {:jwt-token jwt-token
                :jwt-verified jwt-verified
                :jwt-decoded jwt-decoded
                }]
      {:body (json/write-str resp)
       :headers json-mime-type
       :status 200})))

(defonce hot-reload-routes
  ;; Reload changed files without server restart
  (if config/hot-reload
    (wrap-reload #'auth-routes)
    auth-routes))

(defonce cors-routes
  ;; Use CORS middleware to support in-browser JavaScript requests.
  (wrap-cors hot-reload-routes #".*"))

(defonce sentry-routes
  ;; Use sentry middleware to report runtime errors if we have a raven DSN.
  (if config/dsn
    (wrap-sentry cors-routes config/dsn)
    cors-routes))

(defonce app
  (wrap-params sentry-routes))

(defn start
  "Start a server"
  [port]
  (run-server app {:port port :join? false})
    (println (str "\n" (slurp (clojure.java.io/resource "./open_company_auth/assets/ascii_art.txt")) "\n"
      "Auth Server\n"
      "Running on port: " port "\n"
      "Hot-reload: " config/hot-reload "\n"
      "Sentry: " config/dsn "\n\n"
      "Ready to serve...\n")))

(defn -main
  "Main"
  []
  (start config/web-server-port))