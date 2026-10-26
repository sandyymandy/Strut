package com.sandymandy.strut.loading.math.function.generic;

import com.sandymandy.strut.loading.math.MathValue;
import com.sandymandy.strut.loading.math.function.MathFunction;

/**
 * {@link MathFunction} value supplier
 *
 * <p>
 * <b>Contract:</b>
 * <br>
 * Returns the square root of the input value
 */
public final class SqrtFunction extends MathFunction {
    private final MathValue value;

    public SqrtFunction(MathValue... values) {
        super(values);

        this.value = values[0];
    }

    @Override
    public String getName() {
        return "math.sqrt";
    }

    @Override
    public double compute() {
        return Math.sqrt(this.value.get());
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
