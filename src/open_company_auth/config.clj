(ns open-company-auth.config
  "Namespace for the configuration parameters."
  (:require [environ.core :refer (env)]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as appenders]))

(defn- bool
  "Handle the fact that we may have true/false strings, when we want booleans."
  [val]
  (boolean (Boolean/valueOf val)))

;; ----- Sentry -----

(defonce dsn (or (env :open-company-sentry-auth) false))

;; ----- Slack -----

(defonce slack-client-id (env :open-company-slack-client-id))
(defonce slack-client-secret (env :open-company-slack-client-secret))

;; ----- HTTP server -----

(defonce hot-reload (bool (or (env :hot-reload) false)))
(defonce auth-server-port (Integer/parseInt (or (env :port) "3003")))

;; ----- URLs -----

(defonce auth-server-url (or (env :auth-server-url) (str "http://localhost:" auth-server-port)))
(defonce ui-server-url (or (env :ui-server-url) "http://localhost:3559"))

;; ----- JWT -----

(defonce passphrase (env :open-company-auth-passphrase))

;; ----- Logging -----

(def log-config
  {:level :info  ; e/o #{:trace :debug :info :warn :error :fatal :report}
   :middleware [] ; (fns [data]) -> ?data, applied left->right
   :timestamp-opts timbre/default-timestamp-opts ; {:pattern _ :locale _ :timezone _}
   :output-fn timbre/default-output-fn ; (fn [data]) -> string
   :appenders {:spit (appenders/spit-appender {:fname "/tmp/oc-api.log"})}})