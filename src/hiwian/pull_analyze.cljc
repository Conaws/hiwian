(ns hiwian.pull-analyze
  (:require [datascript.core :as d]
            [hiwian.util :as util]))

(defn reverse-lookup? [attr]
  (= (first (name attr)) '\_))

(defn reverse-lookup [attr]
  (when (= (first (name attr)) '\_)
    (keyword (str (namespace attr) "/" (reduce str (rest (name attr)))))))

(defn dbid-into-vec [v]
  (if (not (some #{'* :db/id} v))
    (cons :db/id v)
    v))

(defn insert-dbid [pull-pattern]
  (cond
   (map? pull-pattern) (zipmap (keys pull-pattern)
                               (map insert-dbid (vals pull-pattern)))

   (vector? pull-pattern)
   (->> (dbid-into-vec pull-pattern)
        (map #(if (coll? %) (insert-dbid %) % ))
        vec)

   :else pull-pattern))

(defn pull-affected-datoms [pull-fn db pull-pattern eid]
  (pull-fn db (insert-dbid pull-pattern) eid))

(defn pull-ref-one? [v]
  (and (map? v) (:db/id v)))

(defn pull-ref-many? [v]
  (and (vector? v) (pull-ref-one? (first v))))

(defn ref? [schema attr]
  (= (get (get schema attr) :db/valueType)
     :db.type/ref))

(defn cardinality-one? [schema attr]
  (when-let [e (get schema attr)]
    (not (= (:db/cardinality e) :db.cardinality/many))))

(defn cardinality-many? [schema attr]
  (when-let [e (get schema attr)]
    (= (:db/cardinality e) :db.cardinality/many)))

(defn tx-datoms-for-pull-map [schema entity-id pull-map]
  (if (empty? pull-map)
    []
    (let [[k v] (first pull-map)
          r?    (reverse-lookup? k)
          k     (if r? (reverse-lookup k) k)]
      (cond
       (= k :db/id)
       (tx-datoms-for-pull-map schema entity-id (rest pull-map))

       (ref? schema k)
       (concat
        (cond
         (and (not r?) (cardinality-one? schema k))
         (concat
          [[entity-id k (:db/id v)]]
          (tx-datoms-for-pull-map schema (:db/id v) v))

         (or r? (cardinality-many? schema k))
         (concat
          (when (not r?)
            (mapcat #(vector [entity-id k (:db/id %)]) v))
          (mapcat #(tx-datoms-for-pull-map
                    schema
                    (:db/id %)
                    (merge (when r? {k {:db/id entity-id}}) %))
                  v))

         :else [[entity-id k v]])
        (tx-datoms-for-pull-map schema entity-id (rest pull-map)))

       :else
       (concat
        (if (cardinality-many? schema k)
          (mapcat #(vector [entity-id k %]) v)
          [[entity-id k v]])
        (tx-datoms-for-pull-map schema entity-id (rest pull-map)))))))

(defn generate-affected-tx-datoms-for-pull [schema affected-pull]
  (tx-datoms-for-pull-map schema (:db/id affected-pull) affected-pull))

(comment (defn pull-datoms [db pull-pattern ent-id]
           (generate-affected-tx-datoms-for-pull
            db
            (pull-affected-datoms db pull-pattern (d/entid db ent-id)))))

;;;;; pull pattern generator ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  ;; perhaps will use later to reduce the pull pattern verbosity
  (defn count-avs [patterns]
    (if (empty? patterns)
      {}
      (merge-with + {(let [[e a v] (first patterns)] [a v]) 1}
                  (count-avs (rest patterns)))))

  (defn count-eas [patterns]
    (if (empty? patterns)
      {}
      (merge-with + {(let [[e a] (first patterns)] [e a]) 1}
                  (count-eas (rest patterns)))))

  (defn reducible-patterns [pattern-counts]
    (remove nil? (map (fn [[k v]] (when (> v 1) v) k) pattern-counts)))

  (defn combine-ents [patterns])

  (defn combine-patterns [patterns]
    (let [avs (reducible-patterns (count-avs patterns))
          eas (reducible-patterns (count-eas patterns))]
      )))

(defn limit-spec? [x]
  (and (seq? x) (= (first x) 'limit)))

(defn limit-attr [limit-spec]
  (second limit-spec))

(def remove-limits
  (partial
   clojure.walk/postwalk (fn [x] (if (limit-spec? x) (limit-attr x) x))))

(defn recursive-val? [v]
  (or (number? v) (= v '...)))

(defn tx-pattern-for-pull [schema pull-pattern affected-pull]
  (let [entity-keys (remove #(or (map? %) (= :db/id %)) pull-pattern)
        val-keys    (remove #(or (reverse-lookup? %) (ref? schema %)) entity-keys)
        ref-keys    (->> entity-keys
                         (remove (set val-keys))
                         (map (fn [k] {k [:db/id]})))
        starred?    (some #{'*} val-keys)
        pull-maps   (reduce merge (concat ref-keys (filter map? pull-pattern)))]
    (when (:db/id affected-pull)
      (concat
       (when (not (empty? val-keys))
         [[(:db/id affected-pull) (if starred? '_ (set val-keys)) '_]])
       (mapcat (fn [[ref-key ref-pull]]
                 (let [r? (reverse-lookup? ref-key)
                       unrev-key (if r? (reverse-lookup ref-key) ref-key)]
                   (concat
                    (if r?
                      [['_ unrev-key (:db/id affected-pull)]]
                      [[(:db/id affected-pull) ref-key '_]])
                    (cond
                     (recursive-val? ref-pull)
                     (when (ref-key affected-pull)
                       (mapcat #(tx-pattern-for-pull schema pull-pattern %)
                               (ref-key affected-pull)))

                     (or r? (cardinality-many? schema unrev-key))
                     (mapcat #(tx-pattern-for-pull schema ref-pull %)
                             (ref-key affected-pull))
                     :else
                     (tx-pattern-for-pull schema ref-pull (ref-key affected-pull))))))
               pull-maps)))))

(comment
  (defn pull-tx-pattern [db pull-pattern ent-id]
    (let [prepped-pattern (insert-dbid (remove-limits pull-pattern))]
      (tx-pattern-for-pull
       db
       prepped-pattern
       (pull-affected-datoms db pull-pattern (d/entid db ent-id))))))


;;; combo them bad boys

;; retrieve :datoms, :patterns, or :results
(defn pull-analyze [pull-fn q-fn entid-fn retrieve schema db pull-pattern ent-id]
  (when-not (empty? retrieve)
    (let [affected-datoms (pull-affected-datoms pull-fn db pull-pattern (entid-fn db ent-id))]
      (merge
       (when (some #{:results} retrieve)
         {:results affected-datoms})
       (when (some #{:datoms :datoms-t} retrieve)
         (let [datoms (generate-affected-tx-datoms-for-pull schema affected-datoms)]
           (merge
            (when (some #{:datoms} retrieve)
              {:datoms datoms})
            (when (some #{:datoms-t} retrieve)
              {:datoms-t (util/t-for-datoms q-fn db datoms)}))))
       (when (some #{:patterns} retrieve)
         {:patterns
          (tx-pattern-for-pull
           schema
           (insert-dbid (remove-limits pull-pattern))
           affected-datoms)})))))



(comment

  (p/filter conn )

  )
