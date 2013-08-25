;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.test.lock
  (:use
    [lamina.core lock]
    [clojure test]
    [lamina.test utils]))

(deftest test-acquire-all
  (let [num-locks 10
        num-threads 10 
        locks (repeatedly num-locks #(asymmetric-lock))
        striped (->> locks (partition 2) (map first))]
    (doseq [l striped]
      (acquire l))
    (let [results (->>
                    #(future
                       (acquire-all true locks)
                       (Thread/sleep 10)
                       (doseq [l locks]
                         (release-exclusive l))
                       true)
                    (repeatedly num-threads)
                    doall)]
      (Thread/sleep 100)
      (doseq [l striped]
        (release l))
      (is (= (repeat num-threads true) (map deref results))))))

(deftest ^:benchmark benchmark-locks
  (bench "create lock"
    (lock))
  (let [lock (lock)]
    (bench "acquire/release"
      (acquire-exclusive lock)
      (release-exclusive lock)))
  (bench "create asymmetric lock"
    (asymmetric-lock))
  (let [lock (asymmetric-lock)]
    (bench "asymmetric acquire/release"
      (acquire lock)
      (release lock))
    (bench "asymmetric exclusive acquire/release"
      (acquire-exclusive lock)
      (release-exclusive lock))))

