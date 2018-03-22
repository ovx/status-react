(ns status-im.transport.message.v1.group-chat
  (:require [re-frame.core :as re-frame]
            [clojure.set :as set]
            [status-im.utils.handlers :as handlers]
            [status-im.transport.message.core :as message]
            [status-im.i18n :as i18n]
            [status-im.chat.models :as models.chat]
            [status-im.transport.message.v1.protocol :as protocol]
            [status-im.transport.utils :as transport.utils]))


;; NOTE: We ignore the chat-id from the send and receive method.
;; The chat-id is usually deduced from the filter the message comes from but not in that case because it is sent
;; individually to each participant of the group.
;; In order to be able to determine which group the message belongs to the chat-id is therefore
;; passed in the message itself
(defrecord NewGroupKey [chat-id sym-key message]
  message/StatusMessage
  (send [this _ cofx]
    (let [public-keys (map :identity (get-in cofx [:db :chats chat-id :contacts]))]
      (protocol/multi-send-with-pubkey {:public-keys public-keys
                                        :chat-id     chat-id
                                        :payload     this}
                                       cofx)))
  (receive [this _ signature cofx]
    (handlers/merge-fx cofx
                       {:shh/add-new-sym-key {:web3       (get-in cofx [:db :web3])
                                              :sym-key    sym-key
                                              :on-success (fn [sym-key sym-key-id]
                                                            (re-frame/dispatch [::add-new-sym-key {:chat-id    chat-id
                                                                                                   :sym-key    sym-key
                                                                                                   :sym-key-id sym-key-id
                                                                                                   :message    message}]))}}
                       (protocol/init-chat chat-id))))

(defn user-is-group-admin? [chat-id cofx]
  (= (get-in cofx [:db :chats chat-id :group-admin])
     (get-in cofx [:db :current-public-key])))

(defn send-new-group-key [message chat-id cofx]
  (when (user-is-group-admin? chat-id cofx)
    {:shh/get-new-sym-key {:web3 (get-in cofx [:db :web3])
                           :on-success (fn [sym-key sym-key-id]
                                         (re-frame/dispatch [::send-new-sym-key {:chat-id    chat-id
                                                                                 :sym-key    sym-key
                                                                                 :sym-key-id sym-key-id
                                                                                 :message    message}]))}}))

(defn prepare-system-message [admin-name added-participants removed-participants contacts]
  (let [added-participants-names   (map #(get-in contacts [% :name] %) added-participants)
        removed-participants-names (map #(get-in contacts [% :name] %) removed-participants)]
    (cond
      (and added-participants removed-participants)
      (str admin-name " "
           (i18n/label :t/invited) " " (apply str (interpose ", " added-participants-names))
           " and "
           (i18n/label :t/removed) " " (apply str (interpose ", " removed-participants-names)))

      added-participants
      (str admin-name " " (i18n/label :t/invited) " " (apply str (interpose ", " added-participants-names)))

      removed-participants
      (str admin-name " " (i18n/label :t/removed) " " (apply str (interpose ", " removed-participants-names))))))

(defn- init-chat-if-new [chat-id cofx]
  (if (nil? (get-in cofx [:db :transport/chats chat-id]))
    (protocol/init-chat chat-id cofx)))

(defn- participants-diff [existing-participants-set new-participants-set]
  {:removed (set/difference existing-participants-set new-participants-set)
   :added   (set/difference new-participants-set existing-participants-set)})

(defrecord GroupAdminUpdate [chat-name participants]
  message/StatusMessage
  (send [this chat-id cofx]
    (handlers/merge-fx cofx
                       (init-chat-if-new chat-id)
                       (send-new-group-key this chat-id)))
  (receive [this chat-id signature {:keys [now db] :as cofx}]
    (let [me (:current-public-key db)]
      ;; we have to check if we already have a chat, or it's a new one
      (if-let [{:keys [group-admin contacts] :as chat} (get-in db [:chats chat-id])]
        ;; update for existing group chat
        (when (= signature group-admin) ;; make sure that admin is the one making changes
          (let [{:keys [removed added]} (participants-diff (set contacts) (set participants))
                admin-name              (or (get-in cofx [db :contacts/contacts group-admin :name])
                                            group-admin) 
                message-id              (transport.utils/message-id this)]
            (if (removed me) ;; we were removed
              (handlers/merge-fx cofx
                                 {:system-message {:message-id message-id
                                                   :chat-id    chat-id
                                                   :timestamp  now
                                                   :content    (str admin-name " " (i18n/label :t/removed-from-chat))}}
                                 (models.chat/update-chat {:chat-id         chat-id
                                                           :removed-from-at now
                                                           :is-active       false}))
              (handlers/merge-fx cofx
                                 {:system-message {:message-id message-id
                                                   :chat-id    chat-id
                                                   :timestamp  now
                                                   :content    (prepare-system-message admin-name added removed (:contacts/contacts db))}} 
                                 (message/participants-added chat-id added)
                                 (message/participants-removed chat-id removed)))))
        ;; first time we hear about chat -> create it if we are among participants
        (when (get (set participants) me)
          (models.chat/add-group-chat chat-id chat-name signature participants cofx))))))

(defrecord GroupLeave []
  message/StatusMessage
  (send [this chat-id cofx]
    (protocol/send {:payload this
                    :chat-id chat-id}
                   cofx))
  (receive [this chat-id signature {:keys [now] :as cofx}]
    (let [message-id               (transport.utils/message-id this)
          participant-leaving-name (get-in cofx [:db :contacts signature] signature)]
      (handlers/merge-fx cofx
                         {:system-message {:message-id message-id
                                           :chat-id    chat-id
                                           :timestamp  now
                                           :content (str participant-leaving-name " " (i18n/label :t/left))}}
                         (send-new-group-key this chat-id)
                         (message/participants-removed chat-id [signature])))))

(handlers/register-handler-fx
  ::send-new-sym-key
  ;; this is the event that is called when we want to send a message that required first
  ;; some async operations
  (fn [{:keys [db] :as cofx} [_ {:keys [chat-id message sym-key sym-key-id]}]]
    (let [{:keys [web3]} db]
      (handlers/merge-fx cofx
                         {:db (assoc-in db [:transport/chats chat-id :sym-key-id] sym-key-id)
                          :shh/add-filter {:web3 web3
                                           :sym-key-id sym-key-id
                                           :topic (transport.utils/get-topic chat-id)
                                           :chat-id chat-id}
                          :data-store.transport/save {:chat-id chat-id
                                                      :chat (-> (get-in db [:transport/chats chat-id])
                                                                (assoc :sym-key-id sym-key-id)
                                                                ;;TODO (yenda) remove once go implements persistence
                                                                (assoc :sym-key sym-key))}}
                         (message/send (NewGroupKey. chat-id sym-key message) chat-id)))))

(handlers/register-handler-fx
  ::add-new-sym-key
  (fn [{:keys [db] :as cofx} [_ {:keys [sym-key-id sym-key chat-id message]}]]
    (let [{:keys [web3 current-public-key]} db]
      (handlers/merge-fx cofx
                         {:db (assoc-in db [:transport/chats chat-id :sym-key-id] sym-key-id)
                          :shh/add-filter {:web3 web3
                                           :sym-key-id sym-key-id
                                           :topic (transport.utils/get-topic chat-id)
                                           :chat-id chat-id}
                          :data-store.transport/save {:chat-id chat-id
                                                      :chat (-> (get-in db [:transport/chats chat-id])
                                                                (assoc :sym-key-id sym-key-id)
                                                                ;;TODO (yenda) remove once go implements persistence
                                                                (assoc :sym-key sym-key))}}
                         (message/receive message chat-id chat-id)))))
