(ns puppetlabs.trapperkeeper.services.metrics.tracing-core
  "Clojure helpers for constructing and configuring Tracing."
  (:require [clojure.tools.logging :as log]
            [schema.core :as schema]
            [clojure.set :as setutils]
            [ring.util.request :as requtils])
  (:import
    [io.opentracing.propagation Format$Builtin TextMapExtractAdapter]
    [io.opentracing.tag Tags]

    [com.uber.jaeger Tracer$Builder]
    [com.uber.jaeger.context TracingUtils]
    [com.uber.jaeger.samplers ConstSampler]
    [com.uber.jaeger.reporters LoggingReporter RemoteReporter]
    [com.uber.jaeger.metrics InMemoryStatsReporter Metrics StatsFactoryImpl]
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


(defn extract-span-from-req
  [tracer req]
  (-> tracer
       (.buildSpan (requtils/path-info req))
       (.asChildOf (.extract tracer
                             Format$Builtin/TEXT_MAP
                             (TextMapExtractAdapter. (:headers req))))
       (.withTag (.getKey Tags/SPAN_KIND) Tags/SPAN_KIND_SERVER)
       .start))

(defn wrap-with-request-tracing
  "Ring middleware. Wraps the given ring handler with OpenTracing instrumentation."
  [app tracer]
  (fn [req]
    (let [span (extract-span-from-req tracer req)
          traced-req (assoc req :opentracing-span span)]
      (try
        (push-span span)
        (app traced-req)
        (finally
          (.finish span)
          (pop-span))))))

