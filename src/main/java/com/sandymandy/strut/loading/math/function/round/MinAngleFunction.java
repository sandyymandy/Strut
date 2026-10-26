package com.sandymandy.strut.loading.math.function.round;

import net.minecraft.util.math.MathHelper;
import com.sandymandy.strut.loading.math.MathValue;
import com.sandymandy.strut.loading.math.function.MathFunction;

/**
 * {@link MathFunction} value supplier
 *
 * <p>
 * <b>Contract:</b>
 * <br>
 * Minimizes the magnitude of the input value angle (in degrees), clamped between -180 and 180 (inclusive)
 */
public final class MinAngleFunction extends MathFunction {
    private final MathValue value;

    public MinAngleFunction(MathValue... values) {
        super(values);

        this.value = values[0];
    }

    @Override
    public String getName() {
        return "math.min_angle";
    }

    @Override
    public double compute() {
        return MathHelper.wrapDegrees(this.value.get());
    }

    @Override
    public int getMinArgs() {
        return 1;
    }

    @Override
    public MathValue[] getArgs() {
        return new MathValue[] {this.value};
    }
}