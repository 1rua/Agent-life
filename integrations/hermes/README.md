# open-android-intelligence Hermes Gateway

Native Python Gateway Protocol v2 plugin for Hermes.

The plugin requires Python 3.12 or newer. Sensitive Gateway state is only
enabled when the Hermes secret store supplies an authenticated AEAD provider;
without one the plugin fails closed.
