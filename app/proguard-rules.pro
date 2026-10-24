# Release minification is off by default. Before enabling R8 add keep rules for:
#  - com.anthropic.** and com.fasterxml.jackson.** (reflection-based JSON binding)
#  - kotlinx.serialization generated serializers (consumer rules are bundled, usually enough)
