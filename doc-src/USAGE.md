## Usage

The dovecot crate provides a `server-spec` function that returns a
server-spec. This server spec will install and run the dovecot server (not the
dashboard).  You pass a map of options to configure dovecot.  The `:config`
value should be a form that will be output as the
[dovecot configuration](http://dovecot.io/howto.html).

The `server-spec` provides an easy way of using the crate functions, and you can
use the following crate functions directly if you need to.

The `settings` function provides a plan function that should be called in the
`:settings` phase.  The function puts the configuration options into the pallet
session, where they can be found by the other crate functions, or by other
crates wanting to interact with the dovecot server.  The settings are made up of
a `:config` key, with a map as value, specifying the `dovecot.conf`
configuration values.  The :user-strategy key is used to select how dovecot
should be configured - `:passdb-file` is the only current for this, and it takes
it's options from the `:user-strategy-config` key.

The `:supervision` key in the settings allows running dovecot under `:initd`,
`:runit`, `:upstart` or `:nohup`.

The `install` function is responsible for actually installing dovecot.  At
present installation from tarball url is the only supported method.
Installation from deb or rpm url would be nice to add, as these are now
available from the dovecot site.

The `configure` function writes the dovecot configuration file, using the form
passed to the :config key in the `settings` function.

The `run` function starts the dovecot server.


## Live test on vmfest

For example, to run the live test on VMFest, using Ubuntu 12.04:

```sh
lein with-profile +vmfest pallet up --selectors ubuntu-12-04 --phases install,configure,test
lein with-profile +vmfest pallet down --selectors ubuntu-12-04
```
