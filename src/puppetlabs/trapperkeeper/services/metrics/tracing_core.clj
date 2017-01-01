(ns puppetlabs.trapperkeeper.services.metrics.tracing-core
  "Clojure helpers for constructing and configuring Tracing."
  (:require [clojure.tools.logging :as log]
            [schema.core :as schema])
  (:import
    [com.uber.jaeger Tracer$Builder]
    [com.uber.jaeger.context TracingUtils]
    [com.uber.jaeger.samplers ConstSampler]
    [com.uber.jaeger.reporters LoggingReporter RemoteReporter]
    [com.uber.jaeger.metrics InMemoryStatsReporter Metrics StatsFactoryImpl]
    [io.opentracing.propagation Format$Builtin]
    [com.uber.jaeger.propagation.b3 B3TextMapCodec]
    [com.uber.jaeger.senders.zipkin ZipkinSender]))

(def trace-context
  (TracingUtils/getTraceContext))

(defn create-logger
  []
  ;; Shim in Clojure logging.
  (proxy [LoggingReporter] []
    (report [span]
      (log/info (.toString span)))))

(defn create-tracer
  "Builds an io.opentracing.Tracer backed by Jaeger."
  [tracer-name]
  (-> (Tracer$Builder. tracer-name
                       (RemoteReporter.
                         (ZipkinSender/create "http://10.32.170.26:9411/api/v1/spans")
                         100
                         10
                         (Metrics. (-> (InMemoryStatsReporter.) ( StatsFactoryImpl.))))
                       (ConstSampler. true))
      (.registerExtractor Format$Builtin/TEXT_MAP (new B3TextMapCodec))
      .build))

(defn push-span
  [span]
  (.push trace-context span))

(defn pop-span
  []
  (.pop trace-context))

(defn get-current-span
  []
  (.getCurrentSpan trace-context))
