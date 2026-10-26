package com.sandymandy.strut.loading.math.function.generic;

import net.minecraft.util.math.MathHelper;
import com.sandymandy.strut.loading.math.MathValue;
import com.sandymandy.strut.loading.math.function.MathFunction;

/**
 * {@link MathFunction} value supplier
 *
 * <p>
 * <b>Contract:</b>
 * <br>
 * Returns the arc-tangent theta of the input rectangular coordinate values (y,x), with the output converted to degrees
 */
public final class ATan2Function extends MathFunction {
    private final MathValue y;
    private final MathValue x;

    public ATan2Function(MathValue... values) {
        super(values);

        this.y = values[0];
        this.x = values[1];
    }

    @Override
    public String getName() {
        return "math.atan2";
    }

    @Override
    public double compute() {
        return Math.atan2(this.y.get(), this.x.get()) * MathHelper.DEGREES_PER_RADIAN;
    }

    @Override
    public int getMinArgs() {
        return 2;
    }

    @Override
    public MathValue[] getArgs() {
        return new MathValue[] {this.y, this.x};
    }
}
