(ns datacore.ui.view
  (:require [clojure.core.match :as m :refer [match]]
            [clojure.zip :as zip]
            [clojure.spec :as s]
            [datacore.util :as util]
            [datacore.ui.util :as ui-util]
            [datacore.util.geometry :as geom]
            [datacore.cells :as c]
            [datacore.state :as state]
            [datacore.ui.java-fx :as fx]
            [datacore.ui.keys :as keys]
            [datacore.ui.message :as message]
            [datacore.ui.timer :as timer]
            [clojure.walk :as walk])
  (:import [javafx.scene.input KeyEvent MouseEvent]
           [javafx.scene.paint Color]
           [javafx.stage StageStyle]))

(defn- cell-view-type [cell]
  (::type (c/value cell)))

(defmulti build-view
  (fn [x] (or (:type x)
              (when (c/cell-id? x) (cell-view-type x)))))

(defn- focus!-1 [component]
  (fx/run-later!
   (fn []
     (.requestFocus (fx/stage-of component))
     (.requestFocus component))))

(defn focus! [component]
  (when component
    (if-let [c (ui-util/main-component component)]
      (focus!-1 c)
      (focus!-1 component)))
  component)

(def focused-style (str "-fx-border-width: 4 4 4 4;"
                        "-fx-border-color: #155477;"))
(def unfocused-style "-fx-border-width: 0 0 0 0;")

(defmethod fx/fset :dc/indicate-focus?
  [component _ focused?]
  (fx/set-field! component :style (if focused? focused-style unfocused-style)))

(defmethod fx/fset :dc/meta
  [component _ meta]
  (util/add-meta! component meta))

(def nothing-counter (atom 0))
(defn- build-nothing []
  (swap! nothing-counter inc)
  {:fx/type            :scene.layout/border-pane
   :style-class        ["focus-indicator"]
   :center             {:fx/type           :scene.control/label
                        :style-class       ["label" "main-component"]
                        :text              (str "Nothing to show - " @nothing-counter)
                        :focus-traversable true}
   :on-mouse-clicked   (fx/event-handler
                        (fn [e]
                          (focus! (.getTarget e))))})

(defmethod build-view ::nothing
  [tree]
  (build-nothing))

(defmethod build-view nil
  [tree]
  (build-nothing))

(defmethod build-view ::split-pane
  [{:keys [orientation children]}]
  {:fx/type     :scene.control/split-pane
   :items       (map (comp build-view) children)
   :orientation (if (= orientation :horizontal)
                  javafx.geometry.Orientation/HORIZONTAL
                  javafx.geometry.Orientation/VERTICAL)})

(defn- message-line []
  {:fx/type   :scene.control/label
   :text      (c/formula :msg message/current-message)
   :style     "-fx-padding: 0.6em 0.6em 0.6em 0.6em;"
   :text-fill (c/formula (comp {:message Color/BLACK
                                :error   (Color/web "0xF57000")}
                               :type) message/current-message)})

(defn build-window-contents [tree message]
  {:fx/type :scene.layout/border-pane
   :center  (if-not tree
              (build-view {:type ::nothing})
              (build-view tree))
   :bottom  (message-line)})

(def window-style-map
  {:normal      StageStyle/DECORATED
   :undecorated StageStyle/UNDECORATED
   :transparent StageStyle/TRANSPARENT})

;;TODO add this to scene: [:fx/setup #(style/add-stylesheet % "css/default.css")]

;;;;; focus ;;;;;

(def focused-stage (atom nil))
(def stage->component (atom {}))

(c/add-watch!
 state/focused-component :focus-watch
 (fn [_ old new]
   ;; (println "COMPONENT LOST FOCUS:" (fx/tree old))
   ;; (println "COMPONENT FOCUSED:" (fx/tree new))

   (fx/set-field! old :dc/indicate-focus? false)
   (fx/set-field! new :dc/indicate-focus? true)))

(defn- build-scene [{:keys [dimensions root raw-root window-style]}]
  (merge
   {:fx/type          :scene/scene
    :fx/args          (concat
                       [(build-view ::nothing)]
                       dimensions)
    :fx/prop-listener
    [:focus-owner (fn [_ _ _ new]
                    (when new
                      (let [new-focus-indicator (ui-util/focus-indicator-parent new)]
                        (swap! stage->component assoc (fx/stage-of new-focus-indicator) new-focus-indicator)
                        (c/reset! state/focused-component new-focus-indicator))))]}
   (when root
     {:root (build-window-contents root message/current-message)})
   (when raw-root
     {:root raw-root})
   (when (= window-style :transparent)
     {:fill Color/TRANSPARENT})))

(defmethod build-view ::window
  [{:keys [title window-style] :as params}]
  (fx/make-tree
   (merge
    (when window-style
      {:fx/args [(get window-style-map window-style)]})
    (when title
      {:title title})
    {:fx/type          :stage/stage
     :scene            (build-scene params)
     :fx/event-filter  [KeyEvent/ANY (keys/key-handler)]
     :fx/prop-listener
     [:focused (fn [stage _ _ focused?]
                 (when focused?
                   ;;(println "STAGE FOCUSED:" (fx/tree stage))
                   (reset! focused-stage stage)
                   (c/reset! state/focused-component (get @stage->component stage))))]})))

(defmethod build-view ::cell
  [{:keys [cell focused?]}]
  (let [view (build-view cell)]
    (-> view
        (fx/set-fields!
         {:style-class        ["focus-indicator"]
          :dc/indicate-focus? focused?
          :dc/meta            {::type (cell-view-type cell)}
          :fx/event-filter    [MouseEvent/MOUSE_CLICKED (fn [_] (focus! view))]})
        fx/unmanaged)))

;;;;;;;;;;;;;;;;

(defn- layout-children [{:keys [children root] :as node}]
  (or children (when root [root])))

(defn layout-zipper [root]
  (zip/zipper #(some? (layout-children %))
              layout-children
              (fn [node children]
                (if (:children node)
                  (assoc node :children (vec children))
                  (assoc node :root (first children)))) root))

(defn find-focused [tree]
  (some->> (tree-seq layout-children layout-children tree)
           (filter :focused?)
           first))

(defn find-by-id [tree id]
  (some->> (tree-seq layout-children layout-children tree)
           (filter #(= id (:id %)))
           first))

(defn focusable-in-direction [component direction]
  (when component
    (let [{:keys [min-x min-y
                  max-x max-y
                  width height]
           :as bbox}    (fx/bounds-in-screen component)
          hor?          (contains? #{:left :right} direction)
          min           (if hor? min-x min-y)
          #_             (do (prn 'BBOX bbox)
                            (prn 'MIN min))
          max           (if hor? max-x max-y)
          pos           (if hor?
                          (+ min-y (/ height 2))
                          (+ min-x (/ width 2)))
          in-direction? (fn [component]
                          (let [{:keys [min-x min-y max-x max-y] :as bbox}
                                (fx/bounds-in-screen component)]
                            (condp = direction
                              :up    (<= max-y min)
                              :down  (<= max min-y)
                              :left  (<= max-x min)
                              :right (<= max min-x))))
          covers-pos?   (fn [component]
                          (let [{:keys [min-x min-y max-x max-y]}
                                (geom/extend-bbox (fx/bounds-in-screen component) 20)] ;;extend to avoid getting stuck on separators
                            (if hor?
                              (<= min-y pos max-y)
                              (<= min-x pos max-x))))
          candidates    (some->>
                         (fx/find-by-style-class fx/top-level "focus-indicator")
                         (remove #(= % component))
                         (filter (partial in-direction?))
                         (sort-by #(geom/bbox-distance bbox (fx/bounds-in-screen %))))]
      (or (some->> (filter covers-pos? candidates) first)
          (first candidates)))))
