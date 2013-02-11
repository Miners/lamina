;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.stats
  (:use
    [lamina core api]
    [lamina.core.channel :only (mimic)]
    [lamina.stats.utils :only (update)])
  (:require
    [clojure.tools.logging :as log]
    [lamina.time :as t]
    [lamina.stats.moving-average :as avg]
    [lamina.stats.variance :as var]
    [lamina.stats.quantiles :as qnt])
  (:import
    [java.util.concurrent.atomic
     AtomicLong
     AtomicBoolean]))

(defn sum
  "Returns a channel that will periodically emit the sum of all messages emitted by the source
   channel over the last 'period' milliseconds, with a default of 1000.

   It is assumed that all numbers emitted by the source channel are integral values."
  ([ch]
     (sum nil ch))
  ([{:keys [period
            task-queue]
     :or {period 1000
          task-queue (t/task-queue)}}
    ch]
     (let [ch* (channel)
           cnt (AtomicLong. 0)
           latch (AtomicBoolean. false)]
       
       (bridge-join ch ch* "sum"
         (fn [n]

           (.addAndGet cnt (long n))

           (when (.compareAndSet latch false true)
             (siphon
               (periodically period #(.getAndSet cnt 0) task-queue)
               ch*))))
       ch*)))

(defn rate
  "Returns a channel that will periodically emit the number of messages emitted by the source
   channel over the last 'period' milliseconds, with a default of 1000."
  ([ch]
     (rate nil ch))
  ([{:keys [period
            task-queue]
     :or {period 1000
          task-queue  (t/task-queue)}
     :as options}
    ch]
     (->> ch
       (map* (constantly 1))
       (sum options))))

(defn moving-average
  "Returns a channel that will periodically emit the moving average over all messages emitted by
   the source channel every 'period' milliseconds, defaulting to once every five seconds.  This
   moving average is exponentially weighted to the last 'window' milliseconds, defaulting to the
   last five minutes."
  ([ch]
     (moving-average nil ch))
  ([{:keys [period
            window
            task-queue]
     :or {period (t/seconds 5)
          window (t/minutes 5)
          task-queue (t/task-queue)}}
    ch]
     (let [avg (avg/moving-average period window)
           ch* (channel)
           latch (AtomicBoolean. false)]
       
       (bridge-join ch ch* "moving-average"
         (fn [n]

           (update avg (long n))

           (when (.compareAndSet latch false true)
             (siphon
               (periodically period #(deref avg) task-queue)
               ch*))))
       ch*)))

(defn moving-quantiles
  "Returns a channel that will periodically emit a map of quantile values every 'period'
   millseconds, which represent the statistical distribution of values emitted by the source
   channel over the last five minutes.

   The map will be of quantile onto quantile value, so for a uniform distribution of values from
   1..1000, it would emit

     {50 500, 75 750, 95 950, 99 990, 99.9 999}

   By default, the above quantiles will be used, these can be specified as a sequence of quantiles
   of the form [50 98 99.9 99.99]."
  ([ch]
     (moving-quantiles nil ch))
  ([{:keys [period
            window
            quantiles
            task-queue]
     :or {quantiles [50 75 95 99 99.9]
          period (t/seconds 5)
          task-queue (t/task-queue)
          window (t/minutes 5)}}
    ch]
     (let [ch* (channel)
           latch (AtomicBoolean. false)
           quantiles (qnt/moving-quantiles task-queue window quantiles)]
       (bridge-join ch ch* "moving-quantiles"
         (fn [n]

           (update quantiles (long n))

           (when (.compareAndSet latch false true)
             (siphon
               (periodically period #(deref quantiles) task-queue)
               ch*))))
       ch*)))

(defn variance
  "Returns a channel that will periodically emit the variance of all values emitted by the source
   channel every 'period' milliseconds."
  ([ch]
     (variance nil ch))
  ([{:keys [period
            task-queue]
     :or {period (t/seconds 5)
          task-queue (t/task-queue)}}
    ch]
     (let [vr (atom (var/create-variance))
           ch* (channel)
           latch (AtomicBoolean. false)]
       (bridge-join ch ch* "variance"
         (fn [n]
           (swap! vr update (long n))
           (when (.compareAndSet latch false true)
             (siphon
               (periodically period #(var/variance @vr) task-queue)
               ch*))))
       ch*)))

(defn- abs [x]
  (Math/abs (double x)))

(defn outliers
  "Returns a channel that will emit outliers from the source channel, as measured by the standard
   deviations from the mean value of (facet msg).  Outlier status is determined by
   'variance-predicate', which is given the standard deviations from the mean, and returns true
   or false.  By default, it will return true for any value where the absolute value is greater
   than three.

   For instance, to monitor function calls that take an unusually long or short time via a
   'return' probe:

     (outliers :duration (probe-channel :name:return))

   To only receive outliers that are longer than the mean, define a custom :predicate

     (outliers
       :duration
       {:window (lamina.time/minutes 15)
        :variance-predicate #(< % 3)}
       (probe-channel :name:return))

   :window describes the window of the moving average, which defaults to five minutes.  This can
   be used to adjust the responsiveness to long-term changes to the mean."
  ([facet ch]
     (outliers facet nil ch))
  ([facet
    {:keys [window
            variance-predicate
            task-queue]
     :or {window (t/minutes 5)
          variance-predicate #(< 3 (abs (double %)))
          task-queue (t/task-queue)}}
    ch]
     (let [avg (avg/moving-average (t/seconds 5) window)
           vr (atom (var/create-variance))
           ch* (mimic ch)
           predicates (periodically 5000
                        (fn []
                          (let [mean @avg
                                std-dev (var/std-dev @vr)]
                            (when-not (zero? std-dev)
                              #(variance-predicate (/ (- (facet %) mean) std-dev)))))
                        task-queue)
           f (atom-sink predicates)]
       
       (on-drained ch #(close predicates))

       (bridge-join ch ch* "outliers"
         (fn [msg]
           (when-let [val (facet msg)]
             (update avg val)
             (swap! vr update val)
             (when-let [f @f]
               (when (f val)
                 (enqueue ch* msg))))))
       ch*)))
