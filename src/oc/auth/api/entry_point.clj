(ns oc.auth.api.entry-point
  "Liberator API for HATEOAS entry point to auth service."
  (:require [compojure.core :as compojure :refer (defroutes GET OPTIONS)]
            [liberator.core :refer (defresource)]
            [cheshire.core :as json]
            [oc.lib.db.pool :as pool]
            [oc.lib.api.common :as api-common]
            [oc.auth.config :as config]
            [oc.auth.representations.user :as user-rep]
            [oc.auth.representations.email-auth :as email-auth]
            [oc.auth.representations.slack-auth :as slack-auth]))

;; ----- Representations -----

(defn- render-entry-point [conn {:keys [user] :as _ctx}]

  (if user
    
    ;; auth'd settings
    (json/generate-string
      (user-rep/authed-settings (:user-id user))
      {:pretty config/pretty?})
    
    ;; not auth'd, give them both email and Slack settings
    (json/generate-string
      {:links (conj (concat email-auth/auth-settings
                            slack-auth/auth-settings)
                    user-rep/refresh-link)}
      {:pretty config/pretty?})))

;; ----- Resources - see: http://clojure-liberator.github.io/liberator/assets/img/decision-graph.svg

(defresource entry-point [conn]
  (api-common/anonymous-resource config/passphrase)

  :allowed-methods [:options :get]

  :authorized? true
  :allowed? (fn [ctx] (api-common/allow-anonymous ctx))
  
  ;; Media type client accepts
  :available-media-types ["application/json"]
  :handle-not-acceptable (fn [_] (api-common/only-accept 406 "application/json"))

  ;; Responses
  :handle-ok (fn [ctx] (render-entry-point conn ctx)))

;; ----- Routes -----

(defn routes [sys]
  (let [db-pool (-> sys :db-pool :pool)]
    (compojure/routes
     (OPTIONS "/" [] (pool/with-pool [conn db-pool] (entry-point conn)))
     (GET "/" [] (pool/with-pool [conn db-pool] (entry-point conn))))))