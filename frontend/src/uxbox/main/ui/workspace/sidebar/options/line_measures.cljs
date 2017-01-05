;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.line-measures
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [potok.core :as ptk]
            [uxbox.store :as st]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.geom :as geom]
            [uxbox.util.dom :as dom]
            [uxbox.util.math :refer (precision-or-0)]
            [uxbox.util.data :refer (parse-int parse-float read-string)]))

(defn- line-measures-menu-render
  [own menu shape]
  (letfn [(on-rotation-change [event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)]
              (st/emit! (uds/update-rotation sid value))))
          (on-pos-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value nil)
                  sid (:id shape)
                  props {attr value}]
              (st/emit! (uds/update-line-attrs sid props))))]
    (html
     [:div.element-set {:key (str (:id menu))}
      [:div.element-set-title (:name menu)]
      [:div.element-set-content
       [:span "Position"]
       [:div.row-flex
        [:input.input-text
         {:placeholder "x1"
          :type "number"
          :value (precision-or-0 (:x1 shape 0) 2)
          :on-change (partial on-pos-change :x1)}]
        [:input.input-text
         {:placeholder "y1"
          :type "number"
          :value (precision-or-0 (:y1 shape 0) 2)
          :on-change (partial on-pos-change :y1)}]]

       [:div.row-flex
        [:input.input-text
         {:placeholder "x2"
          :type "number"
          :value (precision-or-0 (:x2 shape 0) 2)
          :on-change (partial on-pos-change :x2)}]
        [:input.input-text
         {:placeholder "y2"
          :type "number"
          :value (precision-or-0 (:y2 shape 0) 2)
          :on-change (partial on-pos-change :y2)}]]

       [:span "Rotation"]
       [:div.row-flex
        [:input.slidebar
         {:type "range"
          :min 0
          :max 360
          :value (:rotation shape 0)
          :on-change on-rotation-change}]]

       [:div.row-flex
        [:input.input-text
         {:placeholder ""
          :type "number"
          :min 0
          :max 360
          :value (precision-or-0 (:rotation shape 0) 2)
          :on-change on-rotation-change
          }]
        [:input.input-text
         {:style {:visibility "hidden"}}]
        ]]]
     )))

(def line-measures-menu
  (mx/component
   {:render line-measures-menu-render
    :name "line-measures-menu"
    :mixins [mx/static]}))