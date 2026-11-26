package io.github.local.esimh4probe;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.window.SplashScreenView;

/** Animates the platform launch surface, shared by cold launches and UI restarts. */
final class LaunchAnimation {
    private LaunchAnimation() {}

    static void install(Activity activity) {
        activity.getSplashScreen().setOnExitAnimationListener(splash -> {
            if (!ValueAnimator.areAnimatorsEnabled() || activity.isFinishing()) {
                splash.remove();
                return;
            }
            animate(splash);
        });
    }

    private static void animate(SplashScreenView splash) {
        AnimatorSet animation = new AnimatorSet();
        ObjectAnimator fade = ObjectAnimator.ofFloat(splash, View.ALPHA, 1f, 0f);
        fade.setStartDelay(160);
        fade.setDuration(280);
        View icon = splash.getIconView();
        if (icon != null) {
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(icon, View.SCALE_X, 0.88f, 1.06f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 0.88f, 1.06f);
            scaleX.setDuration(440);
            scaleY.setDuration(440);
            animation.playTogether(scaleX, scaleY, fade);
        } else {
            animation.playTogether(fade);
        }
        animation.setInterpolator(new DecelerateInterpolator());
        animation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animator) {
                splash.remove();
            }
        });
        splash.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {}

            @Override
            public void onViewDetachedFromWindow(View view) {
                animation.cancel();
            }
        });
        animation.start();
    }
}
