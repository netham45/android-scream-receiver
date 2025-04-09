# Add project specific ProGuard rules here.
# By default, the flags in this file are applied to implementations of the
# AndroidX Library, androidx.fragment.app.Fragment, and other components.
# See https://developer.android.com/studio/build/shrink-code

# Add any specific rules required by libraries your app uses here

# If you use reflection, keep the classes/members you need
#-keep class com.example.MyReflectedClass { *; }

# Keep custom View subclasses' constructors if they are used from XML layouts
#-keep public class * extends android.view.View {
#    public <init>(android.content.Context);
#    public <init>(android.content.Context, android.util.AttributeSet);
#    public <init>(android.content.Context, android.util.AttributeSet, int);
#}

# Keep custom Application subclasses
#-keep public class * extends android.app.Application

# Keep custom Service subclasses
#-keep public class * extends android.app.Service
