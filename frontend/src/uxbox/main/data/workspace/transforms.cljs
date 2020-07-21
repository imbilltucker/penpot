;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.data.workspace.transforms
  "Events related with shapes transformations"
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [uxbox.common.data :as d]
   [uxbox.common.spec :as us]
   [uxbox.common.pages :as cp]
   [uxbox.common.pages-helpers :as cph]
   [uxbox.main.data.workspace.common :as dwc]
   [uxbox.main.data.workspace.selection :as dws]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as ms]
   [uxbox.common.geom.matrix :as gmt]
   [uxbox.common.geom.point :as gpt]
   [uxbox.common.geom.shapes :as gsh]
   [uxbox.main.snap :as snap]))

;; -- Declarations

(declare set-modifiers)
(declare set-rotation)
(declare apply-modifiers)

;; -- Helpers

;; For each of the 8 handlers gives the modifier for resize
;; for example, right will only grow in the x coordinate and left
;; will grow in the inverse of the x coordinate
(def ^:private handler-modifiers
  {:right        [ 1  0]
   :bottom       [ 0  1]
   :left         [-1  0]
   :top          [ 0 -1]
   :top-right    [ 1 -1]
   :top-left     [-1 -1]
   :bottom-right [ 1  1]
   :bottom-left  [-1  1]})

;; Given a handler returns the coordinate origin for resizes
;; this is the opposite of the handler so for right we want the
;; left side as origin of the resize
;; sx, sy => start x/y
;; mx, my => middle x/y
;; ex, ey => end x/y
(defn- handler-resize-origin [{sx :x sy :y :keys [width height]} handler]
  (let [mx (+ sx (/ width 2))
        my (+ sy (/ height 2))
        ex (+ sx width)
        ey (+ sy height)

        [x y] (case handler
                :right [sx my]
                :bottom [mx sy]
                :left [ex my]
                :top [mx ey]
                :top-right [sx ey]
                :top-left [ex ey]
                :bottom-right [sx sy]
                :bottom-left [ex sy])]
    (gpt/point x y)))

(defn finish-transform [state]
  (update state :workspace-local dissoc :transform))

;; -- RESIZE
(defn start-resize
  [handler initial ids shape]
  (letfn [(resize [shape initial resizing-shapes [point lock? point-snap]]
            (let [{:keys [width height rotation]} shape
                  shapev (-> (gpt/point width height))

                  rotation (if (#{:curve :path} (:type shape)) 0 rotation)

                  ;; Vector modifiers depending on the handler
                  handler-modif (let [[x y] (handler-modifiers handler)] (gpt/point x y))

                  ;; Difference between the origin point in the coordinate system of the rotation
                  deltav (-> (gpt/to-vec initial (if (= rotation 0) point-snap point))
                             (gpt/transform (gmt/rotate-matrix (- rotation)))
                             (gpt/multiply handler-modif))

                  ;; Resize vector
                  scalev (gpt/divide (gpt/add shapev deltav) shapev)

                  scalev (if lock? (let [v (max (:x scalev) (:y scalev))] (gpt/point v v)) scalev)

                  shape-transform (:transform shape (gmt/matrix))
                  shape-transform-inverse (:transform-inverse shape (gmt/matrix))

                  ;; Resize origin point given the selected handler
                  origin  (-> (handler-resize-origin shape handler)
                              (gsh/transform-shape-point shape shape-transform))]

              (rx/of (set-modifiers ids
                                    {:resize-vector scalev
                                     :resize-origin origin
                                     :resize-transform shape-transform
                                     :resize-transform-inverse shape-transform-inverse}
                                    false))))

          ;; Unifies the instantaneous proportion lock modifier
          ;; activated by Shift key and the shapes own proportion
          ;; lock flag that can be activated on element options.
          (normalize-proportion-lock [[point shift?]]
            (let [proportion-lock? (:proportion-lock shape)]
              [point (or proportion-lock? shift?)]))

          ;; Applies alginment to point if it is currently
          ;; activated on the current workspace
          ;; (apply-grid-alignment [point]
          ;;   (if @refs/selected-alignment
          ;;     (uwrk/align-point point)
          ;;     (rx/of point)))
          ]
    (reify
      ptk/UpdateEvent
      (update [_ state]
        (-> state
            (assoc-in [:workspace-local :transform] :resize)))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [current-pointer @ms/mouse-position
              initial-position (merge current-pointer initial)
              stoper (rx/filter ms/mouse-up? stream)
              page-id (get state :current-page-id)
              resizing-shapes (map #(get-in state [:workspace-data page-id :objects %]) ids)
              layout (get state :workspace-layout)]
          (rx/concat
           (->> ms/mouse-position
                (rx/with-latest vector ms/mouse-position-shift)
                (rx/map normalize-proportion-lock)
                (rx/switch-map (fn [[point :as current]]
                               (->> (snap/closest-snap-point page-id resizing-shapes layout point)
                                    (rx/map #(conj current %)))))
                (rx/mapcat (partial resize shape initial-position resizing-shapes))
                (rx/take-until stoper))
           #_(rx/empty)
           (rx/of (apply-modifiers ids)
                  finish-transform)))))))


;; -- ROTATE
(defn start-rotate
  [shapes]
  (ptk/reify ::start-rotate
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-local :transform] :rotate)))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper (rx/filter ms/mouse-up? stream)
            group  (gsh/selection-rect shapes)
            group-center (gsh/center group)
            initial-angle (gpt/angle @ms/mouse-position group-center)
            calculate-angle (fn [pos ctrl?]
                              (let [angle (- (gpt/angle pos group-center) initial-angle)
                                    angle (if (neg? angle) (+ 360 angle) angle)
                                    modval (mod angle 45)
                                    angle (if ctrl?
                                            (if (< 22.5 modval)
                                              (+ angle (- 45 modval))
                                              (- angle modval))
                                            angle)
                                    angle (if (= angle 360)
                                            0
                                            angle)]
                                angle))]
        (rx/concat
         (->> ms/mouse-position
              (rx/with-latest vector ms/mouse-position-ctrl)
              (rx/map (fn [[pos ctrl?]]
                        (let [delta-angle (calculate-angle pos ctrl?)]
                          (set-rotation delta-angle shapes group-center))))
              (rx/take-until stoper))
         (rx/of (apply-modifiers (map :id shapes))
                finish-transform))))))

;; -- MOVE

(declare start-move)
(declare start-move-duplicate)

(defn start-move-selected
  []
  (ptk/reify ::start-move-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (let [initial @ms/mouse-position
            selected (get-in state [:workspace-local :selected])
            stopper (rx/filter ms/mouse-up? stream)]
        (->> ms/mouse-position
             (rx/take-until stopper)
             (rx/map #(gpt/to-vec initial %))
             (rx/map #(gpt/length %))
             (rx/filter #(> % 1))
             (rx/take 1)
             (rx/with-latest vector ms/mouse-position-alt)
             (rx/mapcat
              (fn [[_ alt?]]
                (if alt?
                  ;; When alt is down we start a duplicate+move
                  (rx/of (start-move-duplicate initial)
                         dws/duplicate-selected)
                  ;; Otherwise just plain old move
                  (rx/of (start-move initial selected))))))))))

(defn start-move-duplicate [from-position]
  (ptk/reify ::start-move-selected
    ptk/WatchEvent
    (watch [_ state stream]
      (->> stream
           (rx/filter (ptk/type? ::dws/duplicate-selected))
           (rx/first)
           (rx/map #(start-move from-position))))))

(defn start-move
  ([from-position] (start-move from-position nil))
  ([from-position ids]
   (ptk/reify ::start-move
     ptk/UpdateEvent
     (update [_ state]
       (-> state
           (assoc-in [:workspace-local :transform] :move)))

     ptk/WatchEvent
     (watch [_ state stream]
       (let [page-id (get state :current-page-id)
             objects (get-in state [:workspace-data page-id :objects])
             ids     (if (nil? ids) (get-in state [:workspace-local :selected]) ids)
             shapes  (mapv #(get objects %) ids)
             stopper (rx/filter ms/mouse-up? stream)
             layout  (get state :workspace-layout)]
         (rx/concat
          (->> ms/mouse-position
               (rx/take-until stopper)
               (rx/map #(gpt/to-vec from-position %))
               (rx/switch-map #(snap/closest-snap-move page-id shapes objects layout %))
               (rx/map #(gpt/round % 0))
               (rx/map gmt/translate-matrix)
               (rx/map #(set-modifiers ids {:displacement %})))

          (rx/of (apply-modifiers ids)
                 finish-transform)))))))

(defn- get-displacement-with-grid
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [shape direction options]
  (let [grid-x (:grid-x options 10)
        grid-y (:grid-y options 10)
        x-mod (mod (:x shape) grid-x)
        y-mod (mod (:y shape) grid-y)]
    (case direction
      :up (gpt/point 0 (- (if (zero? y-mod) grid-y y-mod)))
      :down (gpt/point 0 (- grid-y y-mod))
      :left (gpt/point (- (if (zero? x-mod) grid-x x-mod)) 0)
      :right (gpt/point (- grid-x x-mod) 0))))

(defn- get-displacement
  "Retrieve the correct displacement delta point for the
  provided direction speed and distances thresholds."
  [direction]
  (case direction
    :up (gpt/point 0 (- 1))
    :down (gpt/point 0 1)
    :left (gpt/point (- 1) 0)
    :right (gpt/point 1 0)))

(s/def ::direction #{:up :down :right :left})

(defn move-selected
  [direction shift?]
  (us/verify ::direction direction)
  (us/verify boolean? shift?)

  (let [same-event (js/Symbol "same-event")]
    (ptk/reify ::move-selected
      IDeref
      (-deref [_] direction)

      ptk/UpdateEvent
      (update [_ state]
        (if (nil? (get-in state [:workspace-local :current-move-selected]))
          (-> state
              (assoc-in [:workspace-local :transform] :move)
              (assoc-in [:workspace-local :current-move-selected] same-event))
          state))

      ptk/WatchEvent
      (watch [_ state stream]
        (if (= same-event (get-in state [:workspace-local :current-move-selected]))
          (let [selected (get-in state [:workspace-local :selected])
                move-events (->> stream
                                 (rx/filter (ptk/type? ::move-selected))
                                 (rx/filter #(= direction (deref %))))
                stopper (->> move-events
                             (rx/debounce 100)
                             (rx/first))
                scale (if shift? (gpt/point 10) (gpt/point 1))
                mov-vec (gpt/multiply (get-displacement direction) scale)]

            (rx/concat
             (rx/merge
              (->> move-events
                   (rx/take-until stopper)
                   (rx/scan #(gpt/add %1 mov-vec) (gpt/point 0 0))
                   (rx/map #(set-modifiers selected {:displacement (gmt/translate-matrix %)})))
              (rx/of (move-selected direction shift?)))

             (rx/of (apply-modifiers selected)
                    (fn [state] (-> state
                                    (update :workspace-local dissoc :current-move-selected))))
             (->>
              (rx/timer 100)
              (rx/map (fn [] finish-transform)))))
            (rx/empty))))))


;; -- Apply modifiers

(defn set-modifiers
  ([ids modifiers] (set-modifiers ids modifiers true))
  ([ids modifiers recurse-frames?]
   (us/verify (s/coll-of uuid?) ids)
   (ptk/reify ::set-modifiers
     ptk/UpdateEvent
     (update [_ state]
       (let [page-id (:current-page-id state)
             objects (get-in state [:workspace-data page-id :objects])
             not-frame-id? (fn [shape-id]
                             (let [shape (get objects shape-id)]
                               (or recurse-frames? (not (= :frame (:type shape))))))

             ;; ID's + Children but remove frame children if the flag is set to false
             ids-with-children (concat ids (mapcat #(cph/get-children % objects)
                                                   (filter not-frame-id? ids)))

             ;; For each shape updates the modifiers given as arguments
             update-shape (fn [state shape-id]
                            (update-in
                             state
                             [:workspace-data page-id :objects shape-id :modifiers]
                             #(merge % modifiers)))]
         (reduce update-shape state ids-with-children))))))

(defn rotation-modifiers [center shape angle]
  (let [displacement (let [shape-center (gsh/center shape)]
                       (-> (gmt/matrix)
                           (gmt/rotate angle center)
                           (gmt/rotate (- angle) shape-center)))]
    {:rotation angle
     :displacement displacement}))


;; Set-rotation is custom because applies different modifiers to each
;; shape adjusting their position.

(defn set-rotation
  ([delta-rotation shapes]
   (set-rotation delta-rotation shapes (-> shapes gsh/selection-rect gsh/center)))

  ([delta-rotation shapes center]
   (ptk/reify ::set-rotation
     ptk/UpdateEvent
     (update [_ state]
       (let [page-id (:current-page-id state)]
         (letfn [(rotate-shape [state angle shape center]
                   (let [objects (get-in state [:workspace-data page-id :objects])
                         path [:workspace-data page-id :objects (:id shape) :modifiers]
                         modifiers (rotation-modifiers center shape angle)]
                     (-> state
                         (update-in path merge modifiers))))

                 (rotate-around-center [state angle center shapes]
                   (reduce #(rotate-shape %1 angle %2 center) state shapes))]

           (let [objects (get-in state [:workspace-data page-id :objects])
                 id->obj #(get objects %)
                 get-children (fn [shape] (map id->obj (cph/get-children (:id shape) objects)))
                 shapes (concat shapes (mapcat get-children shapes))]
             (rotate-around-center state delta-rotation center shapes))))))))

(defn apply-modifiers
  [ids]
  (us/verify (s/coll-of uuid?) ids)
  (ptk/reify ::apply-modifiers
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects0 (get-in state [:workspace-pages page-id :data :objects])
            objects1 (get-in state [:workspace-data page-id :objects])

            ;; ID's + Children ID's
            ids-with-children (d/concat [] (mapcat #(cph/get-children % objects1) ids) ids)

            ;; For each shape applies the modifiers by transforming the objects
            update-shape #(update %1 %2 gsh/transform-shape)
            objects2 (reduce update-shape objects1 ids-with-children)

            regchg   {:type :reg-objects :shapes (vec ids)}

            ;; we need to generate redo chages from current
            ;; state (with current temporal values) to new state but
            ;; the undo should be calculated from clear current
            ;; state (without temporal values in it, for this reason
            ;; we have 3 different objects references).

            rchanges (conj (dwc/generate-changes {:objects objects1} {:objects objects2}) regchg)
            uchanges (conj (dwc/generate-changes {:objects objects2} {:objects objects0}) regchg)
            ]

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
               (dwc/rehash-shape-frame-relationship ids))))))
