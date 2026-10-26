package com.sandymandy.strut.animatable.stateless;

import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animation.RawAnimation;

/**
 * Extension of {@link StatelessAnimatable} for custom {@link StrutAnimatable}s
 * <p>
 * Implementation of {@link StatelessAnimatable#playAnimation(RawAnimation)} and {@link StatelessAnimatable#stopAnimation(String)} is left up
 * to the implementers of this interface, as well as any additional handling required
 */
public non-sealed interface StatelessStrutObject extends StatelessAnimatable, StrutAnimatable {}
