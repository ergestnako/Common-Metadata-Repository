(ns cmr.ingest.data.indexer
  "Implements Ingest App datalayer access interface. Takes on the role of a proxy to indexer app."
  (:require [clj-http.client :as client]
            [cheshire.core :as json]
            [cmr.ingest.config :as config]
            [cmr.common.log :as log :refer (debug info warn error)]
            [cmr.common.services.errors :as errors]
            [cmr.common.services.health-helper :as hh]
            [cmr.system-trace.core :refer [deftracefn]]
            [cmr.system-trace.http :as ch]
            [cmr.transmit.config :as transmit-config]
            [cmr.transmit.connection :as transmit-conn]
            [cmr.message-queue.services.queue :as queue]
            [cmr.acl.core :as acl]
            [clojail.core :as timeout]))

(defn- get-headers
  "Gets the headers to use for communicating with the indexer."
  [context]
  (assoc (ch/context->http-headers context) acl/token-header (transmit-config/echo-system-token)))

(defn- delete-from-index-via-http
  "Execute http delete of the given url on the indexer"
  [context delete-url]
  (let [conn (transmit-config/context->app-connection context :indexer)
        indexer-root (transmit-conn/root-url conn)
        response (client/delete (format "%s/%s" indexer-root delete-url)
                                {:accept :json
                                 :throw-exceptions false
                                 :headers (get-headers context)
                                 :connection-manager (transmit-conn/conn-mgr conn)})
        status (:status response)]
    (when-not (some #{200, 204} [status])
      (errors/internal-error!
        (format "Delete %s operation failed. Indexer app response status code: %s %s"
                delete-url status response)))
    response))

(defn- try-to-publish
  "Attempts to enqueue a message on the message queue.

  When the RabbitMQ server is down or unreachable, calls to publish will throw an exception. Rather
  than raise an error to the caller immediately, the publication will be retried indefinitely.
  By retrying, routine maintenance such as restarting the RabbitMQ server will not result in any
  ingest errors returned to the provider.

  Returns true if the message was successfully enqueued and false otherwise."
  [queue-broker queue-name msg]
  (when-not (try
              (queue/publish queue-broker queue-name msg)
              (catch Exception e
                (error e)
                false))
    (warn "Attempt to queue messaged failed. Retrying: " msg)
    (Thread/sleep 2000)
    (recur queue-broker queue-name msg)))

(defn- put-message-on-queue
  "Put an index operation on the message queue. Throws a service unavailable error if the message
  fails to be put on the queue.

  Requests to publish a message are wrapped in a timeout to handle error cases with the Rabbit MQ
  server. Otherwise failures to publish will be retried indefinitely."
  ([context msg]
   (put-message-on-queue context msg (config/publish-queue-timeout-ms)))
  ([context msg timeout-ms]
   (let [queue-broker (get-in context [:system :queue-broker])
         queue-name (config/index-queue-name)
         start-time (System/currentTimeMillis)]
     (try
       (timeout/thunk-timeout #(try-to-publish queue-broker queue-name msg) timeout-ms)
       (catch java.util.concurrent.TimeoutException e
         (errors/throw-service-error
           :service-unavailable
           (str "Request timed out when attempting to publish message: " msg)
           e))))))

(defn- index-concept-using-http
  "Calls the indexer to index a concept by using the HTTP API."
  [context concept-id revision-id]
  (let [conn (transmit-config/context->app-connection context :indexer)
        indexer-url (transmit-conn/root-url conn)
        concept-attribs {:concept-id concept-id, :revision-id revision-id}
        response (client/post indexer-url {:body (json/generate-string concept-attribs)
                                           :content-type :json
                                           :throw-exceptions false
                                           :accept :json
                                           :headers (get-headers context)
                                           :connection-manager (transmit-conn/conn-mgr conn)})
        status (:status response)]
    (when-not (= 201 status)
      (errors/internal-error!
        (format "Operation to index a concept failed. Indexer app response status code: %d %s"
                status
                response)))))

(defn- enqueue-message
  "Helper function to put a message on the queue for the indexer to process. Valid actions are
  :index-concept and :delete-concept"
  [context concept-id revision-id action]
  (let [msg {:action action
             :concept-id concept-id
             :revision-id revision-id}]
    (put-message-on-queue context msg)))

(defmulti index-concept-with-method
  "Index the concept using an http request or the indexing queue"
  (fn [context concept-id revision-id]
    (keyword (config/indexing-communication-method))))

(defmethod index-concept-with-method :http
  [context concept-id revision-id]
  (index-concept-using-http context concept-id revision-id))

(defmethod index-concept-with-method :queue
  [context concept-id revision-id]
  (try
    (enqueue-message context concept-id revision-id :index-concept)
    (catch Exception e
      (warn "Failed to queue message, will attempt to index concept using http. Exception info: " e)
      (index-concept-using-http context concept-id revision-id))))

(defmulti delete-from-index-with-method
  "Delete the concpet using an http request or the indexing queue"
  (fn [context concept-id revision-id]
    (keyword (config/indexing-communication-method))))

(defmethod delete-from-index-with-method :http
  [context concept-id revision-id]
  (let [delete-url (format "%s/%s" concept-id revision-id)]
    (delete-from-index-via-http context delete-url)))

(defmethod delete-from-index-with-method :queue
  [context concept-id revision-id]
  (try
    (enqueue-message context concept-id revision-id :delete-concept)
    (catch Exception e
      (warn "Failed to queue message, will attempt to delete concept using http. Exception info: " e)
      (let [delete-url (format "%s/%s" concept-id revision-id)]
        (delete-from-index-via-http context delete-url)))))

(deftracefn reindex-provider-collections
  "Re-indexes all the collections in the provider"
  [context provider-ids]
  (let [conn (transmit-config/context->app-connection context :indexer)
        url (format "%s/reindex-provider-collections"
                    (transmit-conn/root-url conn))
        response (client/post url {:content-type :json
                                   :throw-exceptions false
                                   :body (json/generate-string provider-ids)
                                   :accept :json
                                   :headers (get-headers context)
                                   :connection-manager (transmit-conn/conn-mgr conn)})
        status (:status response)]
    (when-not (= 200 status)
      (errors/internal-error!
        (str "Unexpected status"  status  " " (:body response))))))

(deftracefn index-concept
  "Forward newly created concept for indexer app consumption."
  [context concept-id revision-id]
  (index-concept-with-method context concept-id revision-id))

(deftracefn delete-concept-from-index
  "Delete a concept with given revision-id from index."
  [context concept-id revision-id]
  (delete-from-index-with-method context concept-id revision-id))

(deftracefn delete-provider-from-index
  "Delete a provider with given provider-id from index."
  [context provider-id]
  (delete-from-index-via-http context (format "provider/%s" provider-id)))

(defn- get-indexer-health-fn
  "Returns the health status of the indexer app"
  [context]
  (let [conn (transmit-config/context->app-connection context :indexer)
        request-url (str (transmit-conn/root-url conn) "/health")
        response (client/get request-url {:accept :json
                                          :throw-exceptions false
                                          :connection-manager (transmit-conn/conn-mgr conn)})
        {:keys [status body]} response
        result (json/decode body true)]
    (if (= 200 status)
      {:ok? true :dependencies result}
      {:ok? false :problem result})))

(defn get-indexer-health
  "Returns the indexer health with timeout handling."
  [context]
  (let [timeout-ms (* 1000 (+ 2 (hh/health-check-timeout-seconds)))]
    (hh/get-health #(get-indexer-health-fn context) timeout-ms)))
