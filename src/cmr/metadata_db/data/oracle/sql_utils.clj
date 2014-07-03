(ns cmr.metadata-db.data.oracle.sql-utils
  (:require [cmr.common.log :refer (debug info warn error)]
            [cmr.metadata-db.config :as config]
            [clojure.java.jdbc :as j]
            [sqlingvo.core :as s :refer [select from where with order-by desc delete as]]
            [sqlingvo.vendor :as sv]
            [sqlingvo.compiler :as sc]
            [sqlingvo.util :as su]))

(sv/defvendor CmrSqlStyle
              "A defined style for generating sql with sqlingvo that we would use with oracle."
              :name su/sql-name-underscore
              :keyword su/sql-keyword-hyphenize
              :quote identity)

;; Replaces the existing compile-sql function to generate table alias's in the Oracle style which doesn't use the AS word.
;; See https://github.com/r0man/sqlingvo/issues/4
(defmethod sc/compile-sql :table [db {:keys [as schema name]}]
  [(str (clojure.string/join "." (map #(s/sql-quote db %1) (remove nil? [schema name])))
        (when as (str " " (s/sql-quote db as))))])

(defn build
  "Creates a sql statement vector for clojure.java.jdbc."
  [stmt]
  (s/sql (->CmrSqlStyle) stmt))

(defn query
  "Execute a query and log how long it took."
  [db stmt-and-params]
  ;; Uncomment to debug sql
  ; (debug "SQL:" (first stmt-and-params))

  (let [fetch-size (:result-set-fetch-size db)
        start (System/currentTimeMillis)
        result (j/query db (cons {:fetch-size fetch-size} stmt-and-params))
        millis (- (System/currentTimeMillis) start)]
    (debug (format "Query execution took [%d] ms" millis))
    result))

(defn find-one
  "Finds and returns the first item found from a select statment."
  [db stmt]
  (let [stmt (with [:inner stmt]
                   (select ['*]
                           (from :inner)
                           (where '(= :ROWNUM 1))))]
    (first (query db (build stmt)))))
