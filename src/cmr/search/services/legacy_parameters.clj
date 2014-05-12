(ns cmr.search.services.legacy-parameters
  "Contains functions for tranforming legacy parameters to the CMR format."
  (:require [clojure.set :as set]))

(def param-aliases
  "A map of non UMM parameter names to their UMM fields."
  {:dataset-id :entry-title
   :dif-entry-id :entry-id
   :campaign :project
   :online-only :downloadable})

(defn replace-parameter-aliases
  "Replaces aliases of parameter names"
  [params]
  (-> params
      (set/rename-keys param-aliases)
      (update-in [:options]
                 #(when % (set/rename-keys % param-aliases)))))

(defn- process-legacy-range-maps
  "Changes legacy map range conditions in the param[minValue]/param[maxValue] format
  to the cmr format: min,max."
  [concept-type params]
  (reduce-kv (fn [memo k v]
               ;; look for parameters in the map form
               (if (map? v)
                 (let [{:keys [value min-value max-value]} v]
                   (if (or value min-value max-value)
                     ;; convert the map into a comma separated string
                     (if value
                       (assoc memo k value)
                       (assoc memo k (str min-value "," max-value)))
                     memo))
                 memo))
             params
             params))


(defn- process-equator-crossing-date
  "Legacy format for granule equator crossing date is to specify two separate parameters:
  equator-crossing-start-date and equator-crossing-end-date. This function replaces those
  parameters with the current format of start,end."
  [concept-type params]
  (let [{:keys [equator-crossing-start-date equator-crossing-end-date]} params]
    (if (or equator-crossing-start-date equator-crossing-end-date)
      (-> params
          (dissoc :equator-crossing-start-date :equator-crossing-end-date)
          (assoc :equator-crossing-date (str equator-crossing-start-date
                                             ","
                                             equator-crossing-end-date)))
      params)))

;; Add others to this list as needed - note that order is important here
(def legacy-multi-params-condition-funcs
  "A list of functions to call to transform any legacy parameters into CMR form. Each function
  must accept a pair of arguments [concept-type params] where concept-type is :collection,
  :granule, etc. and params is the parameter map generated by the ring middleware."
  [process-equator-crossing-date
   process-legacy-range-maps])

(defn process-legacy-multi-params-conditions
  "Handle conditions that use a legacy range style of using two parameters to specify a range."
  [concept-type params]
  (reduce #(%2 concept-type %1)
          params
          legacy-multi-params-condition-funcs))