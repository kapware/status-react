(ns status-im.contact.db
  (:require [clojure.set :as clojure.set]
            [clojure.string :as clojure.string]
            [status-im.ethereum.core :as ethereum]
            [status-im.utils.gfycat.core :as gfycat]
            [status-im.utils.identicon :as identicon]
            [status-im.multiaccounts.core :as multiaccounts]
            [status-im.constants :as constants]))

(defn public-key->new-contact [public-key]
  (let [alias (gfycat/generate-gfy public-key)]
    {:alias       alias
     :name        alias
     :identicon   (identicon/identicon public-key)
     :public-key  public-key
     :system-tags #{}}))

(defn public-key->contact
  [contacts public-key]
  (when public-key
    (or (get contacts public-key)
        (public-key->new-contact public-key))))

(defn- contact-by-address [[addr contact] address]
  (when (ethereum/address= addr address)
    contact))

(defn find-contact-by-address [contacts address]
  (some #(contact-by-address % address) contacts))

(defn sort-contacts
  [contacts]
  (sort (fn [c1 c2]
          (let [name1 (or (:name c1) (:address c1) (:public-key c1))
                name2 (or (:name c2) (:address c2) (:public-key c2))]
            (compare (clojure.string/lower-case name1)
                     (clojure.string/lower-case name2))))
        (vals contacts)))

(defn filter-dapps
  [v dev-mode?]
  (remove #(when-not dev-mode? (true? (:developer? %))) v))

(defn filter-group-contacts
  [group-contacts contacts]
  (let [group-contacts' (into #{} group-contacts)]
    (filter #(group-contacts' (:public-key %)) contacts)))

(defn query-chat-contacts
  [{:keys [contacts]} all-contacts query-fn]
  (let [participant-set (into #{} (filter identity) contacts)]
    (query-fn (comp participant-set :public-key) (vals all-contacts))))

(defn get-all-contacts-in-group-chat
  [members admins contacts {:keys [public-key] :as current-account}]
  (let [current-contact (some->
                         current-account
                         (select-keys [:name :preferred-name :public-key :identicon :images])
                         (clojure.set/rename-keys {:name           :alias
                                                   :preferred-name :name}))
        all-contacts    (cond-> contacts
                          current-contact
                          (assoc public-key current-contact))]
    (->> members
         (map #(or (get all-contacts %)
                   (public-key->new-contact %)))
         (sort-by (comp clojure.string/lower-case #(or (:name %) (:alias %))))
         (map #(if (get admins (:public-key %))
                 (assoc % :admin? true)
                 %)))))

(defn contact-exists?
  [db public-key]
  (get-in db [:contacts/contacts public-key]))

(defn added?
  ([{:keys [system-tags]}]
   (contains? system-tags :contact/added))
  ([db public-key]
   (added? (get-in db [:contacts/contacts public-key]))))

(def removed? (complement added?))

(defn blocked?
  ([{:keys [system-tags]}]
   (contains? system-tags :contact/blocked))
  ([db public-key]
   (blocked? (get-in db [:contacts/contacts public-key]))))

(defn pending?
  "Check if this is a pending? contact, meaning one side sent a contact request
  but the other didn't respond to it yet"
  ([{:keys [system-tags] :as contact}]
   (let [request-received? (contains? system-tags :contact/request-received)
         added? (added? contact)]
     (and (or request-received?
              added?)
          (not (and request-received? added?)))))
  ([db public-key]
   (pending? (get-in db [:contacts/contacts public-key]))))

(defn legacy-pending?
  "Would the :pending? field be true? for contacts sync payload sent to devices
  running 0.11.0 or older?"
  ([{:keys [system-tags] :as contact}]
   (let [request-received? (contains? system-tags :contact/request-received)
         added? (added? contact)]
     (and request-received?
          (not added?))))
  ([db public-key]
   (pending? (get-in db [:contacts/contacts public-key]))))

(defn active?
  "Checks that the user is added to the contact and not blocked"
  ([contact]
   (and (added? contact)
        (not (blocked? contact))))
  ([db public-key]
   (active? (get-in db [:contacts/contacts public-key]))))

(defn enrich-contact
  ([contact] (enrich-contact contact nil nil))
  ([{:keys [system-tags public-key] :as contact} setting own-public-key]
   (let [added? (contains? system-tags :contact/added)]
     (cond-> (-> contact
                 (dissoc :ens-verified-at :ens-verification-retries)
                 (assoc :pending? (pending? contact)
                        :blocked? (blocked? contact)
                        :active? (active? contact)
                        :added? added?)
                 (multiaccounts/contact-with-names))
       (and setting (not= public-key own-public-key)
            (or (= setting constants/profile-pictures-visibility-none)
                (and (= setting constants/profile-pictures-visibility-contacts-only)
                     (not added?))))
       (dissoc :images)))))

(defn enrich-contacts
  [contacts profile-pictures-visibility own-public-key]
  (reduce-kv (fn [acc public-key contact]
               (assoc acc public-key (enrich-contact contact profile-pictures-visibility own-public-key)))
             {}
             contacts))

(defn get-blocked-contacts
  [contacts]
  (reduce (fn [acc {:keys [public-key] :as contact}]
            (if (blocked? contact)
              (conj acc public-key)
              acc))
          #{}
          contacts))

(defn get-active-contacts
  [contacts]
  (->> contacts
       (filter (fn [[_ contact]]
                 (active? contact)))
       sort-contacts))
