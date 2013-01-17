;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.viz.graph
  (:use
    [lamina.core.utils]
    [lamina.walk]
    [lamina.viz core])
  (:require
    [clojure.string :as str]
    [lamina.core.graph :as g]
    [lamina.core.queue :as q]
    [lamina.core.result :as r]
    [lamina.core.lock :as l]))

;;;

(defn show-queue? [{:keys [error downstream-count consumed? drained? node? grounded?]}]
  (and
    node?
    (not error)
    (not grounded?)
    (not drained?)
    (or consumed? (zero? downstream-count))))

(defn message-str [x]
  (-> x
    pr-str
    (str/replace "{" "\\{")
    (str/replace "}" "\\}")))

(defn message-string [messages]
  (let [cnt (count messages)
        trim? (> cnt 3)
        msgs (take 3 messages)]
    (str
      (when trim? "... |")
      (->> msgs
        reverse
        (map message-str)
        (interpose " | ")
        (apply str)))))

(defn edge-seq->nodes [es]
  (->> es
    (mapcat #(vector (:src %) (:dst %)))
    (remove nil?)
    distinct))

(defn queue-descriptor [n]
  (let [{:keys [messages] :as m} (node-data n)]
    (when (show-queue? m)
      {:shape :Mrecord
       :fontname (if (empty? messages) :times :helvetica)
       :label (str "{"
                (if (empty? messages)
                  "\u2205"
                  (message-string messages))
                "}")})))

(defn node-descriptor [n]
  (let [{:keys [description operator grounded? predicate? closed? error]} (node-data n)
        label (cond

                error
                (str error)

                (and grounded? (not description))
                "\u23DA"
                
                :else
                (or description
                  (when-not (instance? (class identity) operator)
                    (describe-fn operator))
                  ""))]
    {:label label
     :fontcolor (when error :firebrick)
     :color (cond
              closed? :grey
              error :firebrick
              :else nil)
     :width (when (empty? label) 0.25)
     :peripheries (when predicate? 2)}))

(defn edge-descriptor [{:keys [src dst description]}]
  (let [hide-desc? (#{"join" "split" "fork" "tap"} description)
        dotted? (= "tap" description)]
    {:src (if (and (g/node? src) (-> src node-data :consumed?))
            [:queue src]
            src)
     :dst dst
     :label (when-not hide-desc? description)
     :style (when dotted? :dotted)}))

(defn graph-descriptor [root]
  (let [edges (edge-seq root)
        nodes (edge-seq->nodes edges)]
    (merge-with #(if (map? %1) (merge %1 %2) (concat %1 %2))
      ;; normal nodes and edges
      {:nodes (zipmap nodes (map node-descriptor nodes))
       :edges (map edge-descriptor edges)}
      ;; queue nodes and edges
      {:nodes (zipmap
                (map #(vector :queue %) nodes)
                (map queue-descriptor nodes))
       :edges (map
                #(hash-map :src %, :dst [:queue %], :arrowhead :dot)
                nodes)})))

;;;

(defn sample-graph [root msg]
  (let [edges (edge-seq root)
        visible-nodes (->> edges
                        edge-seq->nodes
                        (filter g/node?))
        queue? (comp show-queue? node-data)
        queue-nodes (filter queue? visible-nodes)
        readable-nodes (->> visible-nodes
                         (remove queue?)
                         (remove #(->> % node-data :operator (instance? (class identity)))))]

    ;; lock all the nodes we're going to sample, so we only receive our own message
    (l/acquire-all true readable-nodes)

    ;; read the nodes
    (let [results (zipmap
                    readable-nodes
                    (map g/read-node readable-nodes))
          queues (zipmap
                   queue-nodes
                   (map #(-> % g/queue q/messages count) queue-nodes))]

      ;; send the message
      (g/propagate root msg true)

      ;; error out any results that didn't receive a message
      (doseq [r (vals results)]
        (error r ::nothing-received false))

      ;; release the nodes so other messages can pass through
      (l/release-all true readable-nodes)

      ;; merge the read messages with the last message in the queue of the leaf/queue nodes
      (let [altered-queue-nodes (filter
                                  #(not= (queues %) (-> % g/queue q/messages count))
                                  queue-nodes)]
        (->> results
          (filter (fn [[_ r]] (not= ::none (r/success-value r ::none))))
          (merge (zipmap
                   altered-queue-nodes
                   (map #(-> % g/queue q/messages last r/success-result) altered-queue-nodes)))
          (into {}))))))

(defn trace-descriptor [root msg]
  (let [results (sample-graph root msg)
        descriptor (graph-descriptor root)
        edge-label (fn [e]
                     (when-let [r (results (:src e))]
                       (pr-str (r/success-value r nil))))]
    (-> descriptor
      (update-in [:nodes] assoc :msg {:label (pr-str msg), :width 0, :shape :plaintext})
      (update-in [:edges] (fn [edges] (map #(assoc % :label (edge-label %)) edges)))
      (update-in [:edges] conj {:src :msg :dst root}))))

;;;

(def node-frame (gen-frame "Lamina"))

(def default-settings
  {:options {:rankdir :LR, :pad 0.25, :dpi 100}
   :default-node {:fontname :helvetica, :shape :box}
   :default-edge {:fontname :helvetica}})

(defn render-graph [options & nodes]
  (let [descriptor-merge (fn [a b]
                           (if (map? a)
                             (merge a b)
                             (concat a b)))
        ;; merge all the nodes
        descriptor (->> nodes
                     (map graph-descriptor)
                     (apply merge-with descriptor-merge))
        ;; make sure we have only one of each edge
        descriptor (update-in descriptor [:edges]
                     (fn [edges]
                       (->> edges
                         (map #(select-keys % [:src :dst]))
                         (map (comp vec reverse list) edges)
                         (into {})
                         vals)))
        dot-string (digraph (merge
                              (update-in default-settings [:options]
                                #(merge % options))
                              descriptor))]
    (render-dot-string dot-string)))

(defn render-propagation
  ([options root msg]
     (let [descriptor (trace-descriptor root msg)
           dot-string (digraph (merge
                                 (update-in default-settings [:options]
                                   #(merge % options))
                                 descriptor))]
       (render-dot-string dot-string))))


