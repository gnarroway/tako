(ns tako.tako
  "An implementation of Dataloader for efficient data access using batching/caching."
  (:require [clojure.core.async
             :refer [>! >!! <! chan close! alts! timeout go-loop]]
            [clojure.tools.logging :as log])
  (:import (java.lang AutoCloseable)))


;; Implementation


(defn- batch
  "Collect items on the `in` channel and put them on the `out` channel when
  the collection reaches `max-count`, or `max-time` elapses (whichever is sooner)."
  [in out max-time max-count]
  (let [lim-1 (dec max-count)]
    (go-loop [buf []
              t (timeout max-time)]
      (let [[v p] (alts! [in t])]
        (cond
                 ;; timed out, so process what we got
          (= p t)
          (do
            (when (seq buf)
              (>! out buf))
            (recur [] (timeout max-time)))

                 ;; `in` is closed, so process what we got
          (nil? v)
          (do
            (when (seq buf)
              (>! out buf))
            (close! out))

                 ;; we will fill up the batch, so process it
          (== lim-1 (count buf))
          (do
            (>! out (conj buf v))
            (recur [] (timeout max-time)))

                 ;; accumulate the buffer
          :else
          (recur (conj buf v) t))))))

;;; API

(defrecord Loader [cin promises]
  AutoCloseable
  (close [_]
    (close! cin)))

(defn start!
  "Create a loader.

  Arguments:

  - `fetch-fn` a function that takes a list of keys, and returns a list of results (of the same length),
    corresponding to those keys.
  - `opts` an optional map of keys:
    - `max-batch-size` limits the number of items that get passed in to the `fetch-fn` (default `Integer/MAX_VALUE`).
      Set to 1 to disable batching.
    - `max-batch-time` the maximum time, in ms, to wait before dispatching a non-empty batch to the `fetch-fn`.
      (default 5)."
  ([fetch-fn] (start! fetch-fn nil))
  ([fetch-fn opts]
   (let [{:keys [max-batch-size max-batch-time buffer-size]
          :or {max-batch-size Integer/MAX_VALUE, max-batch-time 5, buffer-size 10000}} opts
         c-in (chan buffer-size)
         c-batches (chan)
         promises (atom {})
         ldr (->Loader c-in promises)]

     ;; Consume batches and deliver promises
     (go-loop []
       (let [ids (<! c-batches)
             ps @promises]

         (cond
                  ;; got a batch to process
           (seq ids)
           (do
             (log/debug "processing id" ids)
             (let [res (try
                         (fetch-fn ids)
                         (catch Exception e
                           e))]
               (if (instance? Exception res)
                 (doseq [k ids]
                          ;; Deliver the exception but clear the cache
                   (deliver (get ps k) res)
                   (swap! promises dissoc k))
                 (doseq [[k v] (zipmap ids res)]
                          ;; Deliver the value
                   (deliver (get ps k) v))))
             (recur))

                  ;; chan is closed
           (nil? ids)
           (log/debug "c-batches closed")

                  ;; keep listening
           :else
           (recur))))

     ;; Collect batches
     (batch c-in c-batches max-batch-time max-batch-size)

     ;; Return the state atom so we can interact with it
     ldr)))

(defn stop!
  "Closes the loader"
  [loader]
  (close! (:cin loader)))

(defn load-one
  "Loads a key `k` from loader `loader`, returning a promise for the value represented by that key."
  [loader k]
  (let [{:keys [cin promises]} loader
        [old new] (swap-vals! promises update k
                              (fn [k] (if k
                                        k
                                        (promise))))]

    ;; enqueue if it has not been fetched previously.
    (when-not (get old k)
      (>!! cin k))

    ;; return the promise tracking the key
    (get new k)))

(defn load-many
  "Loads multiple keys from loader `loader`, promising a list of values corresponding
  to the input list of `ks`."
  [loader ks]
  (deliver (promise) (->> ks
                          (map #(load-one loader %))
                          (map deref))))

(defn clear-one!
  "Clears the value at key `k` from the `loader` cache, if it exists. Returns the loader."
  [loader k]
  (swap! (:promises loader) dissoc k)
  loader)

(defn clear-all!
  "Clears all values from the `loader` cache. Returns the loader."
  [loader]
  (reset! (:promises loader) {})
  loader)

(defn prime!
  "Primes the cache with the provided key and value. If the key already exists, no change is made.
   Returns the loader."
  [loader k v]
  (swap! (:promises loader) update k (fn [v']
                                       (if (some? v')
                                         v'
                                         (deliver (promise) v))))
  loader)

;;; Usage

(comment
  (def batch-fn (fn [ids] (map (fn [id] (str "hello, " id)) ids)))

  (def loader (start! batch-fn {}))
  (deref (load-many loader ["alice" "bob"]))
  (deref (load-one loader "clair"))

  (deref (:promises loader))
  (clear-one! loader "alice")
  (clear-all! loader)
  (prime! loader "clair" "c")

  (stop! loader)

  (with-open [ld (start! batch-fn {})]
    (println) (deref (load-many ld ["alice" "bob"])))

  (with-open [ld (start! batch-fn {})]
    (println (deref (load-one ld "clair")))))