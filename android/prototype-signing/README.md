# Prototype signing key (NOT SECRET)

`prototype.jks` signs CI prototype builds so each new APK installs as an
update over the previous one (keeping the Spam folder and permissions).

It is committed on purpose and its password is `prototype`. Because it is
public, anyone could sign an APK with it, so only install Message Killer
from this repo's own release link.

To replace it with a real secret key later, add the GitHub secrets
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
and `ANDROID_KEY_PASSWORD` (see `plan.md`, Phase 0). The build then uses
those instead. Switching keys requires one final uninstall/reinstall.
