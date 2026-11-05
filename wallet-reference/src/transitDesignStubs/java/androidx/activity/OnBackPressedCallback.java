package androidx.activity;

/** Compile-only declaration; never included in the Activity DEX. */
public abstract class OnBackPressedCallback {
    public OnBackPressedCallback(boolean enabled) { }
    public abstract void handleOnBackPressed();
    public void remove() { }
}
