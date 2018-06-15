(ns rehiring.job-loader
  (:require
    [clojure.walk :as walk]
    [rehiring.utility :as utl]
    [rehiring.db :as rhdb]
    [rehiring.subs :as subs]
    [re-frame.core :as rfr]
    [cljs.pprint :as pp]
    [clojure.string :as str]))

;; --- loading job data -----------------------------------------


(def internOK (js/RegExp. "internship|intern" "i"))
(def nointernOK (js/RegExp. "no internship|no intern" "i"))
(def visaOK (js/RegExp. "visa|visas" "i"))
(def novisaOK (js/RegExp. "no visa|no visas" "i"))
(def onsiteOK (js/RegExp. "on.?site" "i"))
(def remoteOK (js/RegExp. "remote" "i"))
(def noremoteOK (js/RegExp. "no remote" "i"))

(defn job-spec-extend
  "A parsed job (a spec) begins as {:hn-id <HN id>} then
  gets extended as we recursively explore the .aThing. Note that
  not all aThings are jobs, so look for :ok being set"
  [spec dom]

  (let [cn (.-className dom)]
    (when (some #{cn} ["c5a" "cae" "c00" "c9c" "cdd" "c73" "c88"])
      (when-let [rs (.getElementsByClassName dom "reply")]
        (map (fn [e] (.remove e)) (prim-seq rs)))
      (let [child (.-childNodes dom)
            c0 (aget child 0)]

        ;; pre-digest all nodes
        (swap! spec assoc :body [])                         ;; needed?
        (if (and (= 3 (.-nodeType c0))
                 (< 1 (count (filter #{\|} (.-textContent c0)))))

          (let [s (atom {:in-header true
                         :title-seg []})]
            (doseq [n (prim-seq child)]
              (if (:in-header @s)
                (if (and (= 1 (.-nodeType n))
                         (= "P" (.-nodeName n)))
                  (do
                    (swap! s assoc :in-header false)
                    (swap! spec update-in [:body] conj n))
                  (swap! s update-in [:title-seg] conj n))
                (swap! spec update-in [:body] conj n)))

            (let [htext (str/join " | "
                          (map (fn [h] (.-textContent h)) (:title-seg @s)))
                  hseg (map str/trim (str/split htext #"\|"))
                  hsmatch (fn [rx]
                            (not (nil?
                                   (some (fn [h] (.match h rx)) hseg))))]
              ;(println :htext!!! htext)
              ;(println :hseg hseg )
              (swap! spec assoc :OK true)
              (swap! spec assoc :company (nth hseg 0))
              (swap! spec assoc :title-search htext)
              (swap! spec assoc :body-search
                (str/join "*4*2*"
                  (map (fn [n] (.-textContent n)) (:body @spec))))

              (swap! spec assoc :remote (and (hsmatch remoteOK)
                                             (not (hsmatch noremoteOK))))
              (swap! spec assoc :visa (and (hsmatch visaOK)
                                           (not (hsmatch novisaOK))))
              (swap! spec assoc :intern (and (hsmatch internOK)
                                             (not (hsmatch nointernOK))))
              (swap! spec assoc :onsite (hsmatch onsiteOK)))))))

    ;; always fall through, but do not descend into replies
    (when (not= cn "reply")
      (doseq [child (prim-seq (.-children dom))]
        (job-spec-extend spec child)))))

(defn job-spec [dom]
  ;;(println "jobid!" (.-id dom) dom)
  (let [spec (atom {:hn-id (.-id dom)})]
    (doseq [child (prim-seq (.-children dom))]
      (job-spec-extend spec child))
    (when (:OK @spec)
      ;;(println :fini (dissoc @spec :body :body-search))
      @spec)))


;;; --- dev limits -----------------------------
;;; n.b.: these will be limits *per page*

(def ATHING-PARSE-MAX 1000000)
(def JOB-LOAD-MAX 10000)

(defn jobs-collect [ifr-dom]
  (if-let [cont-doc (.-contentDocument ifr-dom)]
    (let [hn-body (aget (.getElementsByTagName cont-doc "body") 0)]
      (let [things (take ATHING-PARSE-MAX (prim-seq (.querySelectorAll hn-body ".athing")))]
        (println :athings (count things))
        (let [jobs (filter #(:OK %) (map job-spec things))]
          (println :ok-jobs (count jobs))
          (set! (.-innerHTML hn-body) "")
          (take JOB-LOAD-MAX jobs))))
    []))                                                    ;; todo need to force []?

(rfr/reg-event-db :month-set
  (fn [db [_ month-hn-id]]
    (let [mo-def (utl/get-monthly-def month-hn-id)]
      (println :month-set month-hn-id)
      (assoc db
        :month-hn-id month-hn-id
        :page-scrapes {}                                    ;; key url, value jobs
        ))))

(rfr/reg-sub :urls-to-scrape
  (fn [__]
    [(rfr/subscribe [:month-hn-id])])

  (fn [[month-hn-id]]
    ;; when page-scrapes has a key for every urls-to-scrape, the month is loaded
    (let [mo-def (utl/get-monthly-def month-hn-id)]
      (println :queueing-page-loads month-hn-id mo-def)
      ;; we start with a dictionary of loading tasks keyed by the file URLs to be loaded
      ;; when all tasks have the initial :unloaded token replaced (by a vector of jobs), the derived
      ;; :month-jobs sub will take on its value by concat-ing them all
      (if (pos? (:pgCount mo-def))
        (map (fn [pg-offset]
               ;; files are numbered off-by-one to match the page param on HN
               (pp/cl-format nil "files/~a/~a.html" month-hn-id (inc pg-offset)))
          (range (:pgCount mo-def)))
        ;; next we see some advanced Lisp format-ese, backing up to re-use hn-id
        [(pp/cl-format nil "files/~a/~:*~a.html" month-hn-id)]))))

;;; --- single page of jobs loading --------------------------------------------

(defn mk-page-loader []
  (fn [src-url]
    [:iframe {:src     src-url
              :on-load #(let [ifr (.-target %)]
                          (println "HN Jobs Page IFrame Loaded!!" src-url)
                          (rfr/dispatch [:month-page-scraped src-url (jobs-collect ifr)]))}]))

(rfr/reg-event-db :month-page-scraped
  (fn [db [_ url jobs]]
    (println :scraped! url (count jobs))
    (assoc-in db [:page-scrapes url] jobs)))

;;; --- month loading ----------------------------------------------------------

(defn job-listing-loader []
  (fn []
    [:div {:style {:display "none"}}
     (let [month-id @(rfr/subscribe [:month-hn-id])
           page-urls @(rfr/subscribe [:urls-to-scrape])]
       (println :job-listing-loader-sees-urls month-id page-urls)
       (doall
         (map (fn [url]
                (println :mk-pageloader url)
                ^{:key url} [mk-page-loader url])
           page-urls)))]))

(rfr/reg-sub :page-scrapes
  (fn [db]
    (:page-scrapes db)))

(rfr/reg-sub :month-jobs
  ;; signal fnxx
  (fn [_ _]
    [(rfr/subscribe [:urls-to-scrape])
     (rfr/subscribe [:page-scrapes])])

  ;; compute
  (fn [[urls scrapes]]
    (println :mojobs-sees urls (keys scrapes))
    (when (= (count urls) (count scrapes))
      (println :bam-all-pages-loaded (map (fn [k v] [k (count v)]) scrapes))
      (apply concat (vals scrapes)))))

;;; --- UI: month selecting by user ------------------------------------------

(defn pick-a-month []
  (let [months (utl/gMonthlies-cljs)]
    [:div {:class "pickAMonth"}
     [:select {:class     "searchMonth"
               :value     @(rfr/subscribe [:month-hn-id])
               :on-change #(do (println :bam-mo (.-value (.-target %)))
                               (rfr/dispatch [:month-set (.-value (.-target %))]))}
      (let []
        (map (fn [mno mo-def]
               (let [{:keys [hnId desc] :as all} mo-def]
                 ^{:key mno} [:option {:key hnId :value hnId} desc]))
          (range)
          months))]
     [:div {:style utl/hz-flex-wrap}
      [utl/view-on-hn {:hidden (nil? @(rfr/subscribe [:month-hn-id]))}
       (pp/cl-format nil "https://news.ycombinator.com/item?id=~a" @(rfr/subscribe [:month-hn-id]))]
      [:span {:style  {:color  "#fcfcfc"
                       :margin "0 12px 0 12px"}
              :hidden (nil? @(rfr/subscribe [:month-hn-id]))}
       (str "Total jobs: " "hhack" #_(count @(rfr/subscribe [:month-jobs])))]]]))

