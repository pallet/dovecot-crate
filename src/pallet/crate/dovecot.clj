(ns pallet.crate.dovecot
  "Install and configure dovecot.

## Links
- http://www.dovecot.org/
- http://wiki2.dovecot.org/BasicConfiguration
- http://www.dovecot.org/doc/dovecot-example.conf
"
  (:require
   [clj-schema.schema :refer [constraints def-map-schema map-schema
                              optional-path predicate-schema seq-schema
                              sequence-of]]
   [clojure.string :as string]
   [clojure.tools.logging :refer [debugf]]
   [pallet.actions :as actions
    :refer [directory packages package-manager remote-file]]
   [pallet.api :as api :refer [plan-fn]]
   [pallet.contracts :refer [any-value check-spec]]
   [pallet.crate :refer [assoc-settings defplan get-settings]]
   [pallet.crate-install :as crate-install]
   [pallet.crate.service
    :refer [supervisor-config supervisor-config-map] :as service]
   [pallet.script.lib :refer [config-root file pid-root spool-root]]
   [pallet.stevedore :refer [fragment]]
   [pallet.utils :refer [apply-map deep-merge maybe-update-in]]
   [pallet.version-dispatch :refer [defmethod-version-plan
                                    defmulti-version-plan]]))

;;; # Settings

;;; Grouped config settings are represented as namespaced keyword keys,
;;;   e.g :auth/default {:mechanisms :plain}
;;; maps to
;;;   auth default {
;;;     mechanisms = plain
;;;     }

;;; if the scope doesn't have a second argument, the namespace is the same as
;;; the name.
;;;   e.g. :client/client
(defn defaults
  []
  {:conf-root (fragment (file (config-root) "dovecot"))
   :run-root (fragment (file (pid-root) "dovecot"))
   :spool-root (fragment (file (spool-root) "dovecot"))
   :user "dovecot"
   :owner "dovecot"
   :group "dovecot"
   :login-user "dovenull"
   :vmail-user "vmail"
   :vmail-group "vmail"
   :vmail-root "/var/vmail"
   :vmail-path "/var/vmail/%d/%n/Maildir"
   ;; supervisor to run dovecot under
   :supervisor :initd
   :service-name "dovecot"
   ;; allow different uid/gid strategies
   :user-strategy :passwd-file
   :user-strategy-config {}
   ;; static defaults for dovecot config
   :config
   {:protocols [:imaps] ; :imap :pop3 :pop3s
    :disable_plaintext_auth false
    :auth_mechanisms [:plain :login]
    ;; :socket/listen
    ;; {:master/master
    ;;  {:path "/var/run/dovecot/auth-master"
    ;;   :mode "0600"
    ;;   :user "vmail"}
    ;;  :client/client
    ;;  {:path "/var/spool/postfix/private/auth"
    ;;   :mode "0660"
    ;;   :user "postfix"
    ;;   :group "postfix"}}
    }})

(defn derived-defaults
  "Sets defaults based on other (possibly user supplied) settings"
  [{:keys [conf-root login-user run-root user
           vmail-group vmail-user vmail-path vmail-root]
    :as settings}]
  (let [settings (->
                  settings
                  (update-in
                   [:dovecot-conf]
                   #(or % (fragment (file ~conf-root "dovecot.conf"))))
                  ;; (update-in
                  ;;  [:socket/listen :master/master :path]
                  ;;  #(or % (fragment (file run-root "auth-master"))))
                  ;; (update-in
                  ;;  [:socket/listen :client/client :path]
                  ;;  #(or % (fragment (file spool-root "auth-client"))))
                  (update-in
                   [:config :mail_location]
                   #(or % (str "maildir:" vmail-root vmail-path)))
                  (update-in [:config :default_internal_user] #(or % user))
                  (update-in [:config :default_login_user] #(or % login-user))
                  (update-in [:config :mail_uid] #(or % vmail-user))
                  (update-in [:config :mail_gid] #(or % vmail-group)))]
      (update-in settings
       [:run-command]
       #(or % (str "/usr/sbin/dovecot -F -c " (:dovecot-conf settings))))))

(defmulti user-strategy-settings
  "Update settings for the given user strategy."
  (fn user-strategy-settings-eval [settings]
    (debugf "user-strategy-settings %s" (:user-strategy settings))
    (:user-strategy settings)))

(defmethod user-strategy-settings :passwd-file
  [{:keys [conf-root user] :as settings}]
  (let [settings
        (-> settings
            (update-in [:user-strategy-config :passwd-file]
                       #(or % (fragment (file ~conf-root "passwd"))))
            (update-in [:user-strategy-config :passwd-owner] #(or % user))
            ;; Change the default auth-woker from root to the dovecot internal
            ;; user, in order to reduce privilege use.
            (update-in [:service/auth-worker :user] #(or % user)))]
    (-> settings
        (update-in [:config :passdb :driver] #(or % "passwd-file"))
        (update-in [:config :passdb :args]
                   #(or % (-> settings :user-strategy-config :passwd-file)))
        (update-in [:config :userdb :driver] #(or % "passwd-file"))
        (update-in [:config :userdb :args]
                   #(or % (-> settings :user-strategy-config :passwd-file))))))


;;; ## Installation Settings

;;; At the moment we just have a single implementation of settings,
;;; but this is open-coded.
(defmulti-version-plan settings-map [version settings])

(defmethod-version-plan
    settings-map {:os :linux}
    [os os-version version settings]
  (cond
   (:install-strategy settings) settings
   :else  (assoc settings
            :install-strategy :packages
            :packages ["dovecot-imapd" "dovecot-pop3d"])))

(defmethod supervisor-config-map [:dovecot :initd]
  [_ {:keys [service-name] :as settings} options]
  {:service-name service-name})

(defmethod supervisor-config-map [:dovecot :runit]
  [_ {:keys [run-command service-name] :as settings} options]
  {:service-name service-name
   :run-file {:content (str "#!/bin/sh\nexec 1>&2\n"
                            "exec " run-command)}})

(defmethod supervisor-config-map [:dovecot :upstart]
  [_ {:keys [run-command service-name] :as settings} options]
  {:service-name service-name
   :exec run-command})

(defmethod supervisor-config-map [:dovecot :nohup]
  [_ {:keys [run-command service-name] :as settings} options]
  {:service-name service-name
   :run-file {:content run-command}})

(defn settings
  [{:keys [conf-root run-rot spool-root config] :as settings}
   & {:keys [instance-id] :as options}]
  (let [settings (deep-merge (defaults) settings)
        settings (derived-defaults (settings-map (:version settings) settings))
        settings (user-strategy-settings settings)]
    (debugf "Settings %s" settings)
    (assoc-settings :dovecot settings options)
    (supervisor-config :dovecot settings (or options {}))))

;;; # User
(defplan user
  "Create the dovecot user"
  [{:keys [instance-id] :as options}]
  (let [{:keys [group owner user login-user vmail-user vmail-group]}
        (get-settings :dovecot options)]
    (actions/group group :system true)
    (actions/group vmail-group :system true)
    (actions/user owner :group group :system true :shell :false)
    (actions/user user :group group :system true :shell :false)
    (actions/user
     login-user :group "nogroup" :system true :shell :false
     :home "/nonexistent")
    (actions/user vmail-user :group vmail-group :system true :shell :false)))

;;; # Install
(defplan install
  "Install dovecot."
  [{:keys [instance-id]}]
  (let [{:keys [install-strategy owner group log-dir
                vmail-user vmail-group vmail-root]
         :as settings}
        (get-settings :dovecot {:instance-id instance-id})]
    (crate-install/install :dovecot instance-id)
    (when log-dir
      (directory log-dir :owner owner :group group :mode "0755"))
    (directory vmail-root :owner vmail-user :group vmail-group :mode "0700")))

;;; # Configuration

;;; ## Configuration Formatters
(declare format-conf)

(defn format-key
  [key]
  (if (or (nil? (namespace key))
          (= (namespace key) (name key)))
    (name key)
    (str (namespace key) " " (name key))))

(defn value-separator
  [value]
  (if (map? value) " " " = "))

(defn format-value
  [value]
  (cond
   (= false value) "no"
   (= true value) "yes"
   (keyword value) (name value)
   (map? value) (str "{\n" (format-conf value) "\n}")
   (sequential? value) (string/join " " (map format-value value))
   :else (str value)))

(defn format-entry
  [[key value]]
  (string/join "" [(format-key key)
                   (value-separator value)
                   (format-value value)]))

(defn format-conf
  [m]
  (string/join \newline (map format-entry m)))


;;; ## Configuration Helpers
(defn passdb-sql
  [config-file]
  {:pre [config-file]}
  {:passdb/sql {:args config-file}})

(defn passdb
  [driver config]
  {:pre [driver config]}
  {:passdb {:driver driver
            :args config}})

(defn userdb-static
  [{:keys [uid gid home allow-all-users]
    :or {uid 5000 gid 5000 allow-all-users false}}]
  {:pre [home]}
  {:userdb/static
   {:args (format "uid=%s gid=%s home=%s allow_all_users=%s"
                  uid gid home (if allow-all-users "yes" "no"))}})

(defn protocol-lda
  [{:keys [log-path mail-plugins postmaster]
    :or {log-path ""}}]
  {:protocol/lda
   {:auth_socket_path "/var/run/dovecot/auth-master"
    :postmaster_address postmaster
    :mail_plugins (string/join ", " mail-plugins)
    :log_path log-path}})

(defn password-sql-conf
  "Return a configuration map for a SQL query based password configuration."
  [{:keys [driver connect scheme query host dbname user password]
    :or {driver "mysql"
         scheme "PLAIN-MD5"
         host "127.0.0.1"}}]
  {:driver driver
   :connect (or connect
                (format "host=%s dbname=%s user=%s password=%s"
                        host dbname user password))
   :password_query
   (or query
       "SELECT email as user, password FROM virtual_users WHERE email='%u'")
   :default_pass_scheme scheme})

(defn passwd-entry
  "Return a passwd file entry for a user map with :user, :pw, :uid, :gid,
  and :home keys."
  [{:keys [user pw uid gid] :as user-map}]
  (string/join
   ","
   (map user-map [:user :pw :uid :gid :gecos :home :shell :extra-fields])))

(defn passwd-file-content
  "Return passwd file content for a sequence of user maps with :user, :pw,
  :uid, :gid, and :home keys."
  [passwd-entries]
  (string/join \newline (map passwd-entry passwd-entries)))

(defmulti user-strategy-config
  "Write configuration for the given user strategy."
  (fn user-strategy-config-eval [settings] (:user-strategy settings)))

(defmethod user-strategy-config :passwd-file
  [{:keys [user-strategy-config] :as settings}]
  (let [{:keys [passwd-file passwd-entries passwd-owner]} user-strategy-config]
    (debugf "user-strategy-config settings %s" settings)
    (assert passwd-entries
            (str ":passwd-entries in the :user-strategy-config key"
                 " are mandatory for the passwd-file user strategy"))
    (remote-file
     passwd-file
     :owner passwd-owner
     :mode "600"
     :content (passwd-file-content passwd-entries)
     :literal true)))


(defn dovecot-conf-file
  [{:keys [config dovecot-conf owner group] :as settings}]
  (remote-file
   dovecot-conf
   :content (format-conf config)
   :mode "640" :owner owner :group group))

(defn configure
  [{:keys [instance-id] :as options}]
  (let [settings (get-settings :dovecot options)]
    (debugf "Dovecot configure settings %s" settings)
    (dovecot-conf-file settings)
    (user-strategy-config settings)))

;;; # Run
(defplan service
  "Run the dovecot service."
  [& {:keys [action if-flag if-stopped instance-id]
      :or {action :manage}
      :as options}]
  (let [{:keys [supervision-options] :as settings}
        (get-settings :dovecot {:instance-id instance-id})]
    (service/service settings (merge supervision-options
                                     (dissoc options :instance-id)))))

;;; # Server Spec
(defn server-spec
  [settings & {:keys [instance-id] :as options}]
  (api/server-spec
   :phases {:settings (plan-fn
                        (apply-map
                         pallet.crate.dovecot/settings settings options))
            :install (plan-fn
                       (install options)
                       (user options))
            :configure (plan-fn (configure options))}
   :default-phases [:install :configure]))
