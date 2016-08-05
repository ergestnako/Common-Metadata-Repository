(ns cmr.message-queue.queue.sqs
  "Implements index-queue functionality using Amazon SQS.
  Note: the terms 'exchange' and 'topic' are used interchangeably in
  comments here. Topics in SNS are (mostly) equivalent to exchanges in RabbitMQ."
  (:gen-class)
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clojure.core.async :as a]
            [clojure.string :as str]
            [cmr.common.config :as cfg :refer [defconfig]]
            [cmr.common.dev.record-pretty-printer :as record-pretty-printer]
            [cmr.common.lifecycle :as lifecycle]
            [cmr.common.log :as log :refer (debug info warn error)]
            [cmr.common.mime-types :as mt]
            [cmr.common.services.errors :as errors]
            [cmr.common.services.health-helper :as hh]
            [cmr.common.util :as u]
            [cmr.message-queue.config :as config]
            [cmr.message-queue.services.queue :as queue])
  (:import com.amazonaws.auth.policy.actions.SQSActions
           com.amazonaws.auth.policy.Condition
           com.amazonaws.auth.policy.conditions.ConditionFactory
           com.amazonaws.auth.policy.Policy
           com.amazonaws.auth.policy.Principal
           com.amazonaws.auth.policy.Statement
           com.amazonaws.auth.policy.Statement$Effect
           com.amazonaws.services.sqs.AmazonSQSClient
           com.amazonaws.services.sns.AmazonSNSClient
           com.amazonaws.services.sqs.model.CreateQueueRequest
           com.amazonaws.services.sqs.model.GetQueueUrlResult
           com.amazonaws.services.sqs.model.PurgeQueueRequest
           com.amazonaws.services.sqs.model.ReceiveMessageRequest
           com.amazonaws.services.sqs.model.SendMessageResult
           com.amazonaws.services.sqs.model.SetQueueAttributesRequest
           com.amazonaws.ClientConfiguration
           java.util.ArrayList
           java.util.HashMap
           java.io.IOException))

(defconfig queue-polling-timeout
 "Number of seconds SQS should wait before giving up on an attempt to read data from a queue."
 {:default 20
  :type Long})

(def queue-arn-attribute
  "String used by the AWS SQS API to identify the attribute containing a queue's
  Amazon Resource Name"
  "QueueArn")

(defn- dead-letter-queue
  "Returns the dead-letter-queue name for a given queue name. The
  given queue name should already be normalized."
  [queue]
  (str queue "_dead_letter_queue"))

(defn- arn->name
  "Convert an Amazon Resource Name (ARN) to a name (topic, queue, etc.)."
  [arn]
  (str/replace arn #".*:" ""))

(defn- normalize-queue-name
  "Replace dots with underscores. This is needed because SQS only allows
  alpha-numeric chars plus dashes and underscores in queue names, while
  CMR has dots (periods) in queue names."
  [queue-name]
  (str/replace queue-name "." "_"))

(defn- -get-topic
  "Returns the Topic with the given display name."
  [sns-client exchange-name]
  (debug "Calling SNS to get topic " exchange-name)
  (let [exchange-name (normalize-queue-name exchange-name)
        topics (into [] (.getTopics (.listTopics sns-client)))]
   (some (fn [topic]
             (let [topic-arn (.getTopicArn topic)
                   topic-name (arn->name topic-arn)]
               (when (= exchange-name topic-name)
                     topic)))
     topics)))

(def get-topic
 "Memoized function that returns the Topic with the given display name."
 (memoize -get-topic))

(defn- get-queue-arn
  "Get the Amazon Resource Name (ARN) for the given queue.
  See http://docs.aws.amazon.com/general/latest/gr/aws-arns-and-namespaces.html.
  Queue name must be normalized."
  [sqs-client queue-name]
  (let [q-url (.getQueueUrl (.getQueueUrl sqs-client queue-name))
        q-attrs (->> (ArrayList. [queue-arn-attribute])
                     (.getQueueAttributes sqs-client q-url)
                     .getAttributes
                     (into {}))]
   (get q-attrs queue-arn-attribute)))

(defn- create-async-handler
  "Creates a thread that will asynchronously pull messages off the queue, pass them to the handler,
  and process the response."
  [queue-broker queue-name handler]
  (debug  "Starting listener for queue: " queue-name)
  (let [queue-name (normalize-queue-name queue-name)
        sqs-client (get queue-broker :sqs-client)
        queue-url (.getQueueUrl (.getQueueUrl sqs-client queue-name))
        rec-req (ReceiveMessageRequest. queue-url)]
    ;; Only take one message at a time from the queue.
    (.setMaxNumberOfMessages rec-req (Integer. 1))
    ;; Tell SQS how long to wait before returning with no data (long polling).
    (.setWaitTimeSeconds rec-req (Integer. (queue-polling-timeout)))
    (a/thread
      (try
        (u/while-let [rec-result (.receiveMessage sqs-client rec-req)]
          (when-let [msg (first (.getMessages rec-result))]
            (let [msg-body (.getBody msg)
                  msg-content (json/decode msg-body true)]
              (try
                (handler msg-content)
                (.deleteMessage sqs-client queue-url (.getReceiptHandle msg))
                (catch Throwable e
                  (error e "Message processing failed for message" (pr-str msg) "on queue" queue-name))))))
        (catch Throwable e
          (error  e "Async handler for queue" queue-name "completing."))))))

(defn- create-queue
 "Create a queue and its dead-letter-queue if they don't already exist and connect the two."
 [sqs-client queue-name]
 (let [q-name (normalize-queue-name queue-name)
       dlq-name (dead-letter-queue q-name)
       ;; Create thde dead-letter-queue first and get its url
       dlq-url (.getQueueUrl (.createQueue sqs-client dlq-name))
       dlq-arn (get-queue-arn sqs-client dlq-name)
       create-queue-request (CreateQueueRequest. q-name)
       ;; the policy that sets retries and what dead-letter-queue to use
       redrive-policy (str "{\"maxReceiveCount\":\"5\", \"deadLetterTargetArn\": \"" dlq-arn "\"}")
       ;; create the primary queue
       queue-url (.getQueueUrl (.createQueue sqs-client q-name))
       q-attrs (HashMap. {"RedrivePolicy" redrive-policy "VisibilityTimeout" "30"})
       set-queue-attrs-request (doto (SetQueueAttributesRequest.)
                                     (.setAttributes q-attrs)
                                     (.setQueueUrl queue-url))]
    (.setQueueAttributes sqs-client set-queue-attrs-request)))

(defn- create-exchange
 "Creaete an SNS topic to be used as an exchange."
 [sns-client exchange-name]
 (.createTopic sns-client (normalize-queue-name exchange-name)))

(defn- topic-conditions
  "Returns a sequence of Conditions allowing the given exchanges access to a queue.
  These will be applied to an explicit queue later."
  [sns-client exchange-names]
  (map (fn [exchange-name]
           (let [ex-name (normalize-queue-name exchange-name)
                 topic (get-topic sns-client ex-name)
                 topic-arn (.getTopicArn topic)]
            (ConditionFactory/newSourceArnCondition topic-arn)))
      exchange-names))

(defn- sns-to-sqs-access-policy
  "Returns an access policy allowing the given SNS topic to publish to the given SQS queue."
  [sns-client sqs-client queue-name exchange-names]
  (let [conditions (topic-conditions sns-client exchange-names)
        statement (doto (Statement. Statement$Effect/Allow)
                        (.withPrincipals (into-array Principal [Principal/AllUsers]))
                        (.withActions (into-array SQSActions [SQSActions/SendMessage]))
                        (.withConditions (into-array Condition conditions)))
        policy (.withStatements (Policy.) (into-array Statement [statement]))]
    (.toJson policy)))

(defn- bind-queue-to-exchanges
 "Bind a queue to SNS Topics representing exchanges."
 [sns-client sqs-client exchange-names queue-name]
 (let [q-name (normalize-queue-name queue-name)
       q-arn (get-queue-arn sqs-client q-name)
       q-url (.getQueueUrl (.getQueueUrl sqs-client q-name))
       ;; create an access policy to allow the topic to publish to the queue
       access-policy (sns-to-sqs-access-policy sns-client sqs-client queue-name exchange-names)
       q-attrs (HashMap. {"Policy" access-policy})

       ;; create and empty SetQueueAttributesRequest object and then set the attributes on it
       set-queue-attrs-request (doto (SetQueueAttributesRequest.)
                                     (.setAttributes q-attrs)
                                     (.setQueueUrl q-url))]
    ;; make the call to set the access policy attribute on the queue
    (.setQueueAttributes sqs-client set-queue-attrs-request)
    (doseq [exchange-name exchange-names
            :let [ex-name (normalize-queue-name exchange-name)
                  topic (get-topic sns-client ex-name)
                  topic-arn (.getTopicArn topic)]]
      ;; subscribe the queue to the topic
      (.subscribe sns-client topic-arn "sqs" q-arn))))

(defn- normalized-queue-name->original-queue-name
  "Convert a normalized queue name to the original queue name used to create it."
  [queue-broker queue-name]
  (get-in queue-broker [:normalized-queue-names queue-name] queue-name))

(defn- -get-queue-url
  "Returns the queue url for the given queue name."
  [sqs-client queue-name]
  (debug "Calling SQS to get URL for queue " queue-name)
  (.getQueueUrl (.getQueueUrl sqs-client queue-name)))

(def get-queue-url
  "Memoized function that returns the queue url for the given name."
  (memoize -get-queue-url))

(defrecord SQSQueueBroker
  ;; A record containig fields related to accessing SNS/SQS exchanges and queues.
  [
   ;; Connection to AWS SNS
   sns-client

   ;; Connection to AWS SQS
   sqs-client

   ;; queues known to this broker
   queues

   ;; map of normalized queue names to original queue names - needed for testing with queue broker wrapper
   ;; See normalize-queue-name for an explanation of normalized queue names.
   normalized-queue-names

   ;; exchanges (topics) known to this broker
   exchanges

   ;; a map of queues to seqeunces of exchange names to which they should be bound
   queues-to-exchanges]

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  lifecycle/Lifecycle

  (start
    [this system]
    (let [sqs-client (AmazonSQSClient.)
          sns-client (AmazonSNSClient.)
          normalized-queue-names (reduce (fn [m queue-name]
                                             (let [nqn (normalize-queue-name queue-name)]
                                               (assoc m nqn queue-name)))
                                         {}
                                         queues)]
      (doseq [queue-name queues]
        (create-queue sqs-client queue-name))
      (doseq [exchange-name exchanges]
        (create-exchange sns-client exchange-name))
      (doseq [queue (keys queues-to-exchanges)
              :let [exchanges (get queues-to-exchanges queue)]]
        (bind-queue-to-exchanges sns-client sqs-client exchanges queue))
      (assoc this :sns-client sns-client :sqs-client sqs-client :normalized-queue-names normalized-queue-names)))

  (stop
    [this system]
    (.shutdown sns-client)
    (.shutdown sqs-client)
    this)

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  queue/Queue

  (publish-to-queue
    [this queue-name msg]
    (let [msg (json/generate-string msg)
          queue-name (normalize-queue-name queue-name)
          queue-url (get-queue-url sqs-client queue-name)]
      (.sendMessage sqs-client queue-url msg)))

  (get-queues-bound-to-exchange
    [this exchange-name]
    (let [exchange-name (normalize-queue-name exchange-name)
          topic (get-topic sns-client exchange-name)
          topic-arn (.getTopicArn topic)
          subs (into [] (.getSubscriptions (.listSubscriptionsByTopic sns-client topic-arn)))]
      (map (fn [sub]
               (->> sub
                    .getEndpoint
                    arn->name
                    (normalized-queue-name->original-queue-name this)))
           subs)))

  (publish-to-exchange
    [this exchange-name msg]
    (let [msg (json/generate-string msg)
          exchange-name (normalize-queue-name exchange-name)
          topic (get-topic sns-client exchange-name)
          topic-arn (.getTopicArn topic)]
      (.publish sns-client topic-arn msg)))

  (subscribe
    [this queue-name handler]
    (let [queue-name (normalize-queue-name queue-name)]
      (create-async-handler this queue-name handler)))

  (reset
    [this]
    (let [sqs-client (:sqs-client this)]
      (doseq [queue (:queues this)
              :let [queue-name (normalize-queue-name queue)
                    dlq-name (dead-letter-queue queue-name)
                    queue-url (.getQueueUrl (.getQueueUrl sqs-client queue-name))
                    dlq-url (.getQueueUrl (.getQueueUrl sqs-client dlq-name))
                    q-purge-req (PurgeQueueRequest. queue-url)
                    dlq-purge-req (PurgeQueueRequest. dlq-url)]]
        (.purgeQueue sqs-client q-purge-req)
        (.purgeQueue sqs-client dlq-purge-req))))

  (health
    [this]
    ;; try to get a list of queues and topics (exchanges) to test the connection to SNS/SQS
    (try
      (.listQueues sqs-client)
      (.listTopics sns-client)
      {:ok? true}
      (catch Throwable e
        {:ok? false :msg (.getMessage e)}))))

(record-pretty-printer/enable-record-pretty-printing SQSQueueBroker)

(defn create-queue-broker
  "Creates a broker that uses SNS/SQS"
  [{:keys [queues exchanges queues-to-exchanges]}]
  (->SQSQueueBroker nil nil queues nil exchanges queues-to-exchanges))

;; Tests to make sure SNS/SQS is working
(comment
  ;; create a broker
  (def broker (lifecycle/start (create-queue-broker {}) nil))
  ;; list the topics for the cmr-ingest_exchange exchange/topic
  (get-topic (:sns-client broker) "cmr_ingest_exchange")
  ;; list the queues for the cmr_ingest_exchange
  (queue/get-queues-bound-to-exchange broker "cmr_ingest_exchange")
  ;; create a test queue
  (create-queue (:sqs-client broker) "cmr-test.queue")
  ;; subscribe to test queue with a simple handler that prints received messages
  (queue/subscribe broker "cmr-test.queue" (fn [msg] (println "Got Message: " msg)))
  ;; publish a message to the queue to verify our subscribe worked
  (queue/publish-to-queue broker "cmr-test.queue" "{\"body\": \"ABC\"}"))