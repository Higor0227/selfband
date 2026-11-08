# SPDX-License-Identifier: GPL-3.0-or-later
# Keep JNI entry points used by the native audio engine.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
