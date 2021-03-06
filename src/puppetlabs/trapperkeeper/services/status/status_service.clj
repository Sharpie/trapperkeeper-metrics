(ns puppetlabs.trapperkeeper.services.status.status-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [service-context maybe-get-service]]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.trapperkeeper.services.status.status-debug-logging :as status-logging]
            [puppetlabs.trapperkeeper.services.authorization.authorization-service :as tk-auth :refer [AuthorizationService]]
            [schema.core :as schema]
            [puppetlabs.i18n.core :as i18n]))

(defprotocol StatusService
  (register-status [this service-name service-version status-version status-fn]
    "Register a status callback function for a service by adding it to the
    status service context.  status-fn must be a function of arity 1 which takes
    the status level as a keyword and returns the status information for
    the given level.  The return value of the callback function must satisfy
    the puppetlabs.trapperkeeper.services.status.status-core/StatusCallbackResponse
    schema.")
  (get-status
    [this service-name level status-version]
    [this service-name level status-version timeout]
    "Call the status function for a registered service, optionally providing
    a timeout to override the default timeout value for the level."))

(defservice status-service
  StatusService
   {:required [[:WebroutingService add-ring-handler get-route]
               [:ConfigService get-in-config]
               [:SchedulerService interspaced]]
    :optional [AuthorizationService]}

  (init [this context]
    (assoc context :status-fns (atom {})
                   :last-cpu-snapshot (atom {:snapshot {:uptime -1
                                                        :process-cpu-time -1
                                                        :process-gc-time -1}
                                             :cpu-usage -1
                                             :gc-cpu-usage -1})))

  (start [this context]
   (let [config (status-core/validate-config (get-in-config [:status]))
         cpu-snapshot (:last-cpu-snapshot context)]
     (status-core/schedule-bg-tasks interspaced
                                    (partial status-logging/log-status cpu-snapshot)
                                    config
                                    cpu-snapshot)

     (register-status this status-core/status-service-name
                      status-core/status-service-version
                      1
                      (partial status-core/v1-status cpu-snapshot)))
         (log/info (i18n/trs "Registering status service HTTP API at /status"))
    (let [path (get-route this)
          base-handler (status-core/build-handler path (deref (:status-fns context)))
          ; The authorization service is an optional dependency, so it may not be here to use
          handler (if-let [auth-svc (maybe-get-service this :AuthorizationService)]
                    (tk-auth/wrap-with-authorization-check auth-svc base-handler)
                    base-handler)]
      (add-ring-handler this handler))
    context)

  (stop [this context]
    (when-let [status-fns (:status-fns context)]
      (status-core/reset-status-context! status-fns))
    context)

  (register-status [this service-name service-version status-version status-fn]
    (log/infof (i18n/trs "Registering status callback function for service ''{0}'', version {1}" service-name service-version))
    (status-core/update-status-context (:status-fns (service-context this))
                                       service-name service-version status-version status-fn))

  (get-status [this service-name level status-version]
    (get-status this service-name level status-version (status-core/check-timeout level)))

  (get-status [this service-name level status-version timeout]
    (let [status-fn (status-core/get-status-fn (:status-fns (service-context this)) service-name status-version)]
      (status-core/guarded-status-fn-call service-name status-fn level timeout))))
