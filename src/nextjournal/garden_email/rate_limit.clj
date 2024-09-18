(ns nextjournal.garden-email.rate-limit
  (:import (java.util.concurrent TimeUnit)))

(defprotocol KVStore
  (get-val [_ k])
  (set-val! [_ k v]))

(deftype AtomStore [store]
  KVStore
  (get-val [_ k] (get @store k))
  (set-val! [_ k v] (swap! store assoc k v)))

(defn atom-store []
  (->AtomStore (atom {})))

(defn make-limiter [{:keys [store interval interval-time-unit limit]
                     :or   {store (atom-store)
                            interval-time-unit TimeUnit/SECONDS}}]
  (let [tokens-per-millis (/ limit (.toMillis interval-time-unit interval))]
    (fn [bucket-key]
      (let [bucket (get-val store bucket-key)
            now (System/currentTimeMillis)
            last (:timestamp bucket)
            remaining (-> (if (some? bucket)
                            (* (- now last)
                               tokens-per-millis)
                            limit)
                          (+ (get bucket :remaining 0))
                          (min limit))
            over-limit? (< remaining 1)
            retry-after (if over-limit?
                          (-> (- 1 remaining)
                              (/ tokens-per-millis 1000)
                              Math/ceil
                              long)
                          0)
            rl-info {:limited? over-limit?
                     :limit limit
                     :remaining (int remaining)
                     :ratelimit-reset (+ (quot now 1000) retry-after)}]
        (if over-limit?
          (assoc rl-info :retry-after retry-after)
          (do
            (set-val! store bucket-key {:timestamp now :remaining (dec remaining)})
            rl-info))))))

(defn make-rate-limited-fn
  "Returns a rate-limited version of `f` according to `opts`. Limiter key is build from args by applying `key-fn`."
  [opts key-fn f]
  (let [limiter (make-limiter opts)]
    (fn [& args]
      (let [{:keys [limited?]} (limiter (apply key-fn args))]
        (if-not limited?
          {:limited? false :value (apply f args)}
          {:limited? true :fn f :args args})))))

(comment
  (def rate-limited-f
    (make-rate-limited-fn
     {:limit 1 :interval 1
      :interval-time-unit TimeUnit/SECONDS} :name :count))

  [(rate-limited-f {:name "x" :count 1})
   (rate-limited-f {:name "x" :count 2})
   (rate-limited-f {:name "y" :count 3})])
