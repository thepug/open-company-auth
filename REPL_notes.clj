;; productive set of development namespaces
(require '[oc.auth.jwt :as jwt] :reload)
(require '[oc.auth.slack :as slack] :reload)
(require '[oc.auth.app :as app] :reload)

;; print last exception
(print-stack-trace *e)