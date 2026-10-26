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
 * Returns the sine of the input value angle, with the input angle converted to radians
 */
public final class SinFunction extends MathFunction {
    private final MathValue value;

    public SinFunction(MathValue... values) {
        super(values);

        this.value = values[0];
    }

    @Override
    public String getName() {
        return "math.sin";
    }

    @Override
    public double compute() {
        return Math.sin(this.value.get() * MathHelper.RADIANS_PER_DEGREE);
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
