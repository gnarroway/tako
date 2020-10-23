(ns tako.tako-test
  "Core tests"
  (:require [clojure.test :refer :all]
            [tako.tako :as t]))

(deftest test-load-one
  (testing "loads one key"
    (with-open [ld (t/start! identity)]
      (let [res (t/load-one ld :k)]
        (is (= :k @res)))))

  (testing "batches multiple calls"
    (let [calls (atom [])]
      (with-open [ld (t/start! (fn [ks] (swap! calls conj ks) ks))]
        (let [a (t/load-one ld :a)
              b (t/load-one ld :b)
              c (t/load-one ld :c)]
          (is (= :a @a))
          (is (= :b @b))
          (is (= :c @c))
          (is (= [[:a :b :c]] @calls))))))

  (testing "batches multiple calls with max batch-size"
    (let [calls (atom [])]
      (with-open [ld (t/start! (fn [ks] (swap! calls conj ks) ks) {:max-batch-size 2})]
        (let [a (t/load-one ld :a)
              b (t/load-one ld :b)
              c (t/load-one ld :c)]
          (is (= :a @a))
          (is (= :b @b))
          (is (= :c @c))
          (is (= [[:a :b] [:c]] @calls))))))

  (testing "uses cache for multiple calls with same key"
    (let [calls (atom [])]
      (with-open [ld (t/start! (fn [ks] (swap! calls conj ks) ks))]
        (let [a (t/load-one ld :a)
              b (t/load-one ld :b)
              a' (t/load-one ld :a)]
          (is (= :a @a))
          (is (= :b @b))
          (is (= :a @a'))
          (is (= [[:a :b]] @calls))))))

  (testing "uses cache for multiple calls with same key across batches"
    (let [calls (atom [])]
      (with-open [ld (t/start! (fn [ks] (swap! calls conj ks) ks) {:max-batch-size 2})]
        (let [a (t/load-one ld :a)
              b (t/load-one ld :b)
              a' (t/load-one ld :a)
              c (t/load-one ld :c)]
          (is (= :a @a))
          (is (= :b @b))
          (is (= :a @a'))
          (is (= :c @c))
          (is (= [[:a :b] [:c]] @calls))))))

  (testing "caches element-level errors"
    (let [calls (atom [])]
      (with-open [ld (t/start! (fn [ks]
                                 (swap! calls conj ks)
                                 (map (fn [k] (if (= :e k)
                                                (Exception. "ouch")
                                                k)) ks)))]

        (t/load-one ld :a)
        (is (instance? Exception @(t/load-one ld :e)))
        (is (instance? Exception @(t/load-one ld :e)))
        (is (= [[:a :e]] @calls)))))

  (testing "does not cache error if the whole fetch-fn fails"
    (let [call-count (atom 0)]
      (with-open [ld (t/start! (fn [ks]
                                 (if (= 1 (swap! call-count inc))
                                   (throw (Exception. "ouch"))
                                   ks)))]

        (is (instance? Exception @(t/load-one ld :a)))
        (is (= :a @(t/load-one ld :a))))))

  (testing "channel limit default handles large input"
    (let [calls (atom [])]
      (with-open [ld (t/start! (fn [ks] (swap! calls conj ks)
                                 ks) {:max-batch-time 1000 :max-batch-size 1})]
        (dotimes [n 2000]
          (future (t/load-one ld n)))
        (is (= :a @(t/load-one ld :a)) "returns without crashing"))))

  (testing "channel limit with small fixed buffer will fail on large input"
    (let [calls (atom [])]
      (with-open [ld (t/start! (fn [ks] (swap! calls conj ks)
                                 ks) {:max-batch-time 1000 :max-batch-size 1 :buffer-size 1})]

        (is (thrown? Error (do (dotimes [n 2000]
                                 (future (t/load-one ld n)))
                               @(t/load-one ld :a))))))))

(deftest test-load-many
  (testing "loading multiple keys"
    (with-open [ld (t/start! identity)]
      (is (= [:a :b] @(t/load-many ld [:a :b])))))

  (testing "loading multiple keys possibly containing error"
    (with-open [ld (t/start! (fn [ks] (map (fn [k] (if (= :e k)
                                                     (Exception. "ouch")
                                                     k)) ks)))]

      (let [res (t/load-many ld [:e :a :b])
            [e & rest] @res]
        (is (= 3 (count @res)))
        (is (= [:a :b] rest))
        (is (instance? Exception e)))))

  (testing "caches element-level errors"
    (let [calls (atom [])]
      (with-open [ld (t/start! (fn [ks]
                                 (swap! calls conj ks)
                                 (map (fn [k] (if (= :e k)
                                                (Exception. "ouch")
                                                k)) ks)) {:max-batch-size 2})]

        (is (instance? Exception (first @(t/load-many ld [:e :a]))))
        (is (instance? Exception (first @(t/load-many ld [:e]))))
        (is (= [[:e :a]] @calls)))))

  (testing "does not cache error if the whole fetch-fn fails"
    (let [call-count (atom 0)]
      (with-open [ld (t/start! (fn [ks]
                                 (if (= 1 (swap! call-count inc))
                                   (throw (Exception. "ouch"))
                                   ks)))]

        (is (instance? Exception (first @(t/load-many ld [:a]))))
        (is (= [:a] @(t/load-many ld [:a])))))))