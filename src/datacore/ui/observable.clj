(ns datacore.ui.observable
  (:require [datacore.util :as util]
            [dev :as dev])
  (:import [javafx.collections FXCollections ObservableList ListChangeListener ListChangeListener$Change]))

(defmulti observable-list type)

(defmethod observable-list clojure.lang.APersistentVector
  [^java.util.List x]
  (FXCollections/observableArrayList x))

(defn list-change [the-list old new]
  (let [diff    (->> (util/seq-diff old new)
                     (remove #(-> % first (= :same)))
                     (partition-by first))
        idx     (atom -1)
        current #(nth diff @idx)]
    (proxy [ListChangeListener$Change] [the-list]
      (next [] (swap! idx inc) (< @idx (count diff)))
      (reset [] (reset! idx 0))

      (wasAdded [] (some? (#{:add :edit} (ffirst (current)))))
      (wasRemoved [] (some? (#{:delete :edit} (ffirst (current)))))
      (wasUpdated [] (= :edit (ffirst (current))))
      (wasPermutated [] false)

      (getFrom [] (apply + (map count (take @idx diff))))
      (getTo [] (+ (apply + (map count (take @idx diff)))
                   (count (current))))

      (getRemoved [] (if-not (.wasRemoved this) [] (map second (current)))))))

(def atom-observable-list-id (atom -1))
(def atom-observable-registry (atom {}))
(defmethod observable-list clojure.lang.Atom
  [x]
  (proxy [Object ObservableList] []
    (get [i] (nth @x i))
    (size [] (count @x))
    (iterator [] (.iterator @x))
    (forEach [consumer] (.forEach @x consumer))
    (isEmpty [] (empty? @x))
    (setAll [coll] (reset! x coll))
    (addListener [^ListChangeListener listener]
      (let [id (str "observable-list-atom-listener" (swap! atom-observable-list-id inc))]
        (swap! atom-observable-registry {listener id})
        (add-watch x id (fn [k ref old new]
                          (.onChanged listener (list-change this old new))))))
    (removeListener [^ListChangeListener listener]
      (when-let [id (get @atom-observable-registry listener)]
        (remove-watch x id)))))