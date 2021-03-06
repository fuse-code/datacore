(ns datacore.ui.view.table
  (:require [datacore.ui.view :as view]
            [datacore.ui.util :refer [with-status-line callback]]
            [datacore.ui.java-fx :as fx]
            [datacore.ui.interactive :as in :refer [defin]]
            [datacore.cells :as c]
            [datacore.ui.observable :refer [observable-list]])
  (:import [javafx.util Callback]
           [javafx.beans.property ReadOnlyObjectWrapper]
           [java.util Date]
           [javafx.scene.control
            SelectionMode ControlUtils TableView TableColumnBase TableSelectionModel]))

(defmethod fx/fget [TableView :dc/cursor]
  [table _]
  (let [cell (some-> table .getSelectionModel .getSelectedCells first)]
    (when cell
      {:column       (.getTableColumn cell)
       :column-index (.getColumn cell)
       :row          (.getRow cell)})))

(defmethod fx/fset [TableView :dc/cursor]
  [table _ {:keys [row column]}]
  (let [model (some-> table .getSelectionModel)]
    (doto model
      (.clearSelection)
      (.select row (or column (-> table .getColumns first))))))

(defin scroll-to-top
  {:alias :table/scroll-to-top
   :params [[:component ::in/main-component]]}
  [{:keys [component]}]
  (let [first-column (-> component .getColumns first)]
    (.scrollTo component (-> component .getItems first))
    (.scrollToColumn component first-column)
    (doto (-> component .getSelectionModel)
      (.clearSelection)
      (.select 0 first-column))))

(defin scroll-to-bottom
  {:alias :table/scroll-to-bottom
   :params [[:component ::in/main-component]]}
  [{:keys [component]}]
  (let [last-column (-> component .getColumns last)]
    (.scrollTo component (-> component .getItems last))
    (.scrollToColumn component last-column)
    (doto (-> component .getSelectionModel)
      (.clearSelection)
      (.select (-> component .getItems .size dec) last-column))))

(defin scroll-to-first-column
  {:alias :table/scroll-to-first-column
   :params [[:component ::in/main-component]]}
  [{:keys [component]}]
  (.scrollToColumnIndex component 0)
  (when-let [row (:row (fx/get-field component :dc/cursor))]
    (let [column (-> component .getColumns first)]
      (-> component .getSelectionModel (.clearAndSelect row column)))))

(defin scroll-to-last-column
  {:alias :table/scroll-to-last-column
   :params [[:component ::in/main-component]]}
  [{:keys [component]}]
  (let [last-index (-> component .getColumns count dec)]
    (.scrollToColumnIndex component last-index)
    (when-let [row (:row (fx/get-field component :dc/cursor))]
      (let [column (-> component .getColumns last)]
        (-> component .getSelectionModel (.clearAndSelect row column))))))

(def ^:private scroll-to* (-> TableView (.getMethod "scrollTo" (into-array [Integer/TYPE]))))

(defn- scroll-to [table index]
  (fx/run-later!
   #(.invoke scroll-to* table (object-array [(int index)]))))

(defin scroll-up
  {:alias :table/scroll-up
   :params [[:component ::in/main-component]]}
  [{:keys [component]}]
  (let [[first-row last-row] (fx/get-field component :fx/visible-range)
        column               (:column (fx/get-field component :dc/cursor))
        row                  (max 0 (- first-row (- last-row first-row)))]
    (scroll-to component row)
    (fx/set-field! component :dc/cursor {:column column :row row})))

(defin scroll-down
  {:alias :table/scroll-down
   :params [[:component ::in/main-component]]}
  [{:keys [component]}]
  (let [[_ last-row] (fx/get-field component :fx/visible-range)
        column       (:column (fx/get-field component :dc/cursor))]
    (scroll-to component last-row)
    (fx/set-field! component :dc/cursor {:column column :row last-row})))

(defin recenter
  {:alias :table/recenter
   :params [[:table ::in/main-component]]}
  [{:keys [table]}]
  (let [[first-row last-row] (fx/get-field table :fx/visible-range)
        cursor-row           (:row (fx/get-field table :dc/cursor))
        center-row           (+ first-row (/ (- last-row first-row) 2))]
    (scroll-to table (inc (- first-row (- center-row cursor-row))))))

(defn column [name cell-value-fn]
  (fx/make
   :scene.control/table-column
   {:fx/args [name]
    :fx/setup
    #(.setCellValueFactory % (callback (fn [x] (ReadOnlyObjectWrapper. (cell-value-fn (.getValue x))))))}))

#_(defmethod view/build-view :datacore.view/table
  [view-cell]
  (with-status-line
    (fx/make
     :scene.control/table-view
     {:fx/args [(observable-list
                 (c/formula (comp rest :data)
                            view-cell
                            {:label :table-view-data}))]
      :columns (c/formula (fn [source]
                            (map-indexed
                             (fn [i c]
                               (column (str c) #(nth % i)))
                             (first (:data source))))
                          view-cell
                          {:label :table-view-columns})})
    (c/formula :label view-cell {:label :table-status-line})))

(defmethod view/build-cell-view ::view/table
  [view-cell]
  (let [control-cell (c/cell {})
        table        (fx/make-tree
                      {:fx/type  :scene.control/table-view
                       :fx/setup
                       (fn [table]
                         (fx/set-field-in! table [:selection-model :selection-mode] SelectionMode/MULTIPLE)
                         (fx/set-field! table :style-class ["table-view" "main-component"])
                         (fx/set-field-in! table [:selection-model :cell-selection-enabled] true)
                         (-> table
                             .getSelectionModel
                             .getSelectedItems
                             (.addListener
                              (fx/list-change-listener
                               (fn [selected]
                                 ;;(c/swap! control-cell assoc :selection selected)
                                 )))))})
        set-data!    (fn [{:keys [columns column-labels data]}]
                       (fx/run-later!
                        #(doto table
                           (fx/set-field!
                            :columns (map (fn [c]
                                            (column (if-let [l (get column-labels c)] l (str c))
                                                    (fn [row] (get row c))))
                                          columns))
                           (fx/set-field! :items (observable-list data)))))]
    (set-data! (c/value view-cell))
    (c/link-slot! control-cell view-cell 1)
    (c/alter-meta! control-cell assoc :roles #{:control})
    (c/alter-meta! view-cell assoc :roles #{:view})
    (c/add-watch! view-cell :table-view (fn [_ _ new] (set-data! new)))
    (with-status-line
      table (c/formula #(str (:label %) " - "
                             (-> % :data count) " rows - "
                             (-> % :columns count) " columns - "
                             (Date. (:last-modified %))
                             " | select: " (or (some-> % :selection-mode name (str "s")) "cells"))
                       view-cell
                       {:label :table-status-line}))))
