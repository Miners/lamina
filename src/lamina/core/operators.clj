;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.core.operators
  (:use
    [potemkin]
    [lamina.core utils channel threads])
  (:require
    [lamina.core.graph :as g]
    [lamina.core.lock :as l]
    [lamina.core.result :as r]
    [lamina.core.pipeline :as p]
    [clojure.tools.logging :as log])
  (:import
    [lamina.core.lock
     Lock]
    [java.util.concurrent
     ConcurrentLinkedQueue]
    [java.util.concurrent.atomic
     AtomicReferenceArray]
    [java.math
     BigInteger])) 



;;;

;; TODO: think about race conditions with closing the destination channel while a message is en-route
;; hand-over-hand locking in the node when ::consumed?

(deftype+ FinalValue [val])

(defmacro consume
  "something goes here"
  [ch & {:keys [map
                predicate
                initial-value
                reduce
                take-while
                final-messages
                timeout
                description
                channel] :as m}]
  (let [channel? (not (and (contains? m :channel) (nil? channel)))
        take-while? (or channel? take-while)]
    (unify-gensyms
      `(let [src-channel## ~ch
             dst## ~(when channel?
                      (or channel `(mimic src-channel##)))
             dst-node## (when dst## (receiver-node dst##)) 
             initial-val# ~initial-value

             ;; message predicate
             take-while## ~take-while
             take-while## ~(if take-while
                             `(fn [~@(when reduce `(val##)) x#]
                                (and
                                  (or
                                    (nil? dst-node##)
                                    (not (g/closed? dst-node##)))
                                  (take-while##
                                    ~@(when reduce `(val##))
                                    x#)))
                             `(fn [~'& _#]
                                (or
                                  (nil? dst-node##)
                                  (not (g/closed? dst-node##)))))

             ;; general predicate
             predicate## ~predicate
             map## ~map
             reduce## ~reduce
             timeout## ~timeout]

         ;; if we're able to consume, take the unconsume function
         (if-let [unconsume# (g/consume
                               (emitter-node src-channel##)
                               (g/edge
                                 ~(or description "consume")
                                 (if dst##
                                   (receiver-node dst##)
                                   (g/terminal-propagator nil))))]

           ;; and define a more comprehensive cleanup function
           (let [cleanup#
                 (fn [val##]
                   ~@(when (and channel? final-messages)
                       `((let [msgs# (~final-messages val##)]
                           (doseq [msg# msgs#]
                             (enqueue dst## msg#)))))
                   (unconsume#)
                   (when dst## (close dst##))
                   (FinalValue. val##))

                 result#
                 (p/run-pipeline initial-val#
                   {:error-handler (fn [ex#]
                                     (log/error ex# (str "error in " ~description))
                                     (if dst##
                                       (error dst## ex# false)
                                       (p/redirect (p/pipeline (constantly (r/error-result ex#))) nil)))}
                   (fn [val##]
                     ;; if we shouldn't even try to read a message, clean up
                     (if-not ~(if predicate
                                `(predicate## ~@(when reduce `(val##)))
                                true)
                       (cleanup# val##)

                       ;; if we should, call read-channel*
                       (p/run-pipeline
                         (read-channel* src-channel##
                           :on-false ::close
                           :on-timeout ::close
                           :on-drained ::close
                           ~@(when timeout
                               `(:timeout (timeout##)))
                           ~@(when take-while?
                               `(:predicate
                                  ~(if reduce
                                     `(fn [msg#]
                                        (take-while## ~@(when reduce `(val##)) msg#))
                                     `take-while##))))

                         {:error-handler (fn [_#])}

                         (fn [msg##]
                           ;; if we didn't read a message or the destination is closed, clean up
                           (if (or (and dst## (closed? dst##))
                                 (identical? ::close msg##))
                             (cleanup# val##)

                             ;; update the reduce value
                             (p/run-pipeline ~(when reduce `(reduce## val## msg##))

                               {:error-handler (fn [_#])}

                               ;; once the value's realized, emit the next message
                               (fn [val##]
                                 (when dst##
                                   (enqueue dst##
                                     ~(if map
                                        `(map## ~@(when reduce `(val##)) msg##)
                                        `msg##)))
                                 val##)))))))

                   (fn [val#]
                     ;; if this isn't a terminal message from cleanup, restart
                     (if (instance? FinalValue val#)
                       (.val ^FinalValue val#)
                       (p/restart val#))))]
             (if dst##
               dst##
               result#))

           ;; something's already attached to the source
           (if dst##
             (do
               (error dst## :lamina/already-consumed! false)
               dst##)
             (r/error-result :lamina/already-consumed!)))))))

;;;

(defn receive-in-order
  "Consumes messages from the source channel, passing them to 'f' one at a time.  If
   'f' returns a result-channel, consumption of the next message is deferred until
   it's realized.

   If an exception is thrown or the return result is realized as an error, the source
   channel is put into an error state."
  [ch f]
  (consume ch
    :channel nil
    :initial-value nil
    :reduce (fn [_ x]
              (p/run-pipeline x
                {:error-handler (fn [ex]
                                  (log/error ex "error in receive-in-order")
                                  (p/complete nil))}
                f))
    :description "receive-in-order"))

(defn emit-in-order
  "Returns a channel that emits messages one at a time."
  [ch]
  (consume ch
    :description "emit-in-order"))

(defn last*
  "A dual to last.  Returns a result that is realized as the final message from the
   source channel before it was closed.

   (last* (closed-channel 3 2 1)) => 1"
  [ch]
  (consume ch
    :channel nil
    :initial-value nil
    :reduce (fn [_ x] x)
    :description "last*"))

(defn take*
  "A dual to take.

   (take* 2 (channel 1 2 3)) => [1 2]"
  [n ch]
  (consume ch
    :description (str "take* " n)
    :initial-value 0
    :reduce (fn [n _] (inc n))
    :predicate #(< % n)))

(defn take-while*
  "A dual to take-while.

   (take-while* pos? (channel 1 2 0 4)) => [1 2]"
  [f ch]
  (consume ch
    :description "take-while*"
    :take-while f))

(defn reductions*
  "A dual to reductions.

   (reductions* max (channel 1 3 2)) => [1 3 3]"
  ([f ch]
     (let [ch* (mimic ch)]
       (consume ch
         :channel ch*
         :description "reductions*"
         :initial-value (p/run-pipeline (read-channel* ch :on-drained ::drained)
                          {:error-handler (fn [_])}
                          #(let [val (if (= ::drained %)
                                       (f)
                                       %)]
                             (enqueue ch* val)
                             val))
         :reduce f
         :map (fn [v _] v))))
  ([f val ch]
     (consume ch
       :channel (let [ch* (mimic ch)]
                  (enqueue ch* val)
                  ch*)
       :description "reductions*"
       :initial-value val
       :reduce f
       :map (fn [v _] v))))

(defn reduce*
  "A dual to reduce.  Returns a result-channel that emits the final reduced value
   when the source channel has been drained.

   (reduce* max (channel 1 3 2)) => 3"
  ([f ch]
     (consume ch
       :channel nil
       :description "reduce*"
       :initial-value (p/run-pipeline (read-channel* ch :on-drained ::drained)
                        {:error-handler (fn [_])}
                        #(if (= ::drained %)
                           (f)
                           %))
       :reduce f
       :map (fn [v _] v)))
  ([f val ch]
     (consume ch
       :channel nil
       :description "reduce*"
       :initial-value val
       :reduce f
       :map (fn [v _] v))))

(defn partition-
  [n step ch final-messages]
  (remove* nil?
    (consume ch
      :description "partition*"
      :initial-value []
      :reduce (fn [v msg]
                (if (= n (count v))
                  (-> (drop step v) vec (conj msg))
                  (conj v msg)))
      :map (fn [v _]
             (when (= n (count v))
               v))
      :final-messages final-messages)))

(defn partition*
  "A dual to partition.

   (partition* 2 (channel 1 2 3)) => [[1 2]]"
  ([n ch]
     (partition* n n ch))
  ([n step ch]
     (partition- n step ch (constantly nil))))

(defn partition-all*
  "A dual to partition-all.

   (partition-all* 2 (closed-channel 1 2 3)) => [[1 2] [3]]"
  ([n ch]
     (partition-all* n n ch))
  ([n step ch]
     (partition- n step ch
       #(if (= n (count %))
          (partition-all n step (drop step %))
          (partition-all n step %)))))

;;;

(defn channel->lazy-seq-
  [read-fn cleanup-fn]
  (let [msg @(read-fn)]
    (if (= ::end msg)
      (do
        (cleanup-fn)
        nil)
      (cons msg (lazy-seq (channel->lazy-seq- read-fn cleanup-fn))))))

(defn channel->lazy-seq
  "Returns a sequence.  As elements of the sequence are realized, messages from the
   source channel are consumed.  If there are no messages are available to be
   consumed, execution will block until one is available.

   A 'timeout' can be defined, either as a number or a no-arg function that returns a
   number.  Each time the seq must wait for a message to consume, it will only wait
   that many milliseconds before giving up and ending the sequence."
  ([ch]
     (channel->lazy-seq ch nil))
  ([ch timeout]
     (let [timeout-fn (when timeout
                        (if (number? timeout)
                          (constantly timeout)
                          timeout))
           e (g/edge "channel->lazy-seq" (g/terminal-propagator nil))]
       (if-let [unconsume (g/consume (emitter-node ch) e)]
         (channel->lazy-seq-
           (if timeout-fn
             #(read-channel* ch :timeout (timeout-fn), :on-timeout ::end, :on-drained ::end)
             #(read-channel* ch :on-drained ::end))
           unconsume)
         (throw (IllegalStateException. "Can't consume, channel already in use."))))))

(defn channel->seq
  "An eager variant of channel->lazy-seq.  Blocks until the channel has been drained,
   or until 'timeout' milliseconds have elapsed."
  ([ch]
     (g/drain (emitter-node ch)))
  ([ch timeout]
     (let [start (System/currentTimeMillis)
           s (g/drain (emitter-node ch))]
       (doall
         (concat s
           (channel->lazy-seq ch
             #(max 0 (- timeout (- (System/currentTimeMillis) start)))))))))

;;;

(defn concat*
  "A dual to concat.

   (concat* (channel [1 2] [2 3])) => [1 2 3 4]"
  [ch]
  (let [ch* (mimic ch)]
    (bridge-join ch "concat*"
      (fn [s]
        (when-not (empty? s)
          (let [val (enqueue ch* (first s))]
            (doseq [msg (rest s)]
              (enqueue ch* msg))
            val)))
      ch*)
    ch*))

(defn mapcat*
  "A dual to mapcat.

   (mapcat* reverse (channel [1 2] [3 4])) => [2 1 4 3]"
  [f ch]
  (->> ch (map* f) concat*))

(defn periodically
  "Returns a channel.  Every 'period' milliseconds, 'f' is invoked with no arguments
   and the value is emitted as a message."
  [period f]
  (let [ch (channel* :description (str "periodically " (describe-fn f)))]
    (p/run-pipeline (System/currentTimeMillis)

      ;; figure out how long to sleep, given the previous target timestamp
      (fn [timestamp]
        (let [target-timestamp (+ timestamp period)]
          (r/timed-result
            (max 0.1 (- target-timestamp (System/currentTimeMillis)))
            target-timestamp)))

       ;; run the callback, and repeat
      (fn [timestamp]
        (let [result (enqueue ch (f))]
          (when-not (or (= :lamina/error! result)
                      (= :lamina/closed! result))
           (p/restart timestamp)))))
    ch))

(defn sample-every
  "Takes a source channel, and returns a channel that emits the most recent message
   from the source channel every 'period' milliseconds."
  [period ch]
  (let [val (atom ::none)
        ch* (mimic ch)]
    (bridge-join ch (str "sample-every " period)
      #(reset! val %)
      ch*)
    (siphon
      (->> #(deref val) (periodically period) (remove* #(= ::none %)))
      ch*)
    ch*))

(defn partition-every
  "Takes a source channel, and returns a channel that repeatedly emits a collection
   of all messages from the source channel in the last 'period' milliseconds."
  [period ch]
  (let [q (ConcurrentLinkedQueue.)
        drain (fn []
                (loop [msgs []]
                  (if (.isEmpty q)
                    msgs
                    (recur (conj msgs (.remove q))))))
        ch* (mimic ch)]
    (bridge-join ch (str "partition-every " period)
      #(.add q %)
      ch*)
    (siphon (periodically period drain) ch*)
    ch*))

;;;

(defn create-bitset [n]
  (reduce
    #(.setBit ^BigInteger %1 %2)
    (BigInteger/valueOf 0)
    (range n)))

(defn unset-bit [^BigInteger b idx]
  (when b
    (let [b (.clearBit b idx)]
      (when-not (zero? (.bitCount b))
        b))))

(defn combine-latest
  "something goes here"
  [f & channels]
  (let [cnt (count channels)
        bitset (atom (create-bitset cnt))
        vals (AtomicReferenceArray. cnt)
        ch* (channel* :description "combine-latest")]

    (doseq [[idx ch] (map vector (range cnt) channels)]
      (bridge-join ch ""
        (fn [msg]
          (.set vals idx msg)

          (if-not (and @bitset (swap! bitset unset-bit idx))

            :lamina/incomplete
            
            ;; copy value array into argument array
            (let [ary (object-array cnt)]
              (dotimes [i idx]
                (aset ary i (.get vals i)))
              (aset ary idx msg)
              (dotimes [j (- cnt idx)]
                (let [i (+ j idx)]
                  (aset ary i (.get vals i))))

              ;; pass along updated evaluation
              (try
                (enqueue ch* (apply f ary))
                (catch Exception e
                  (error ch* e false))))))
        ch*))

    ch*))
