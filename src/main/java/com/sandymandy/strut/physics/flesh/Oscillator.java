package com.sandymandy.strut.physics.flesh;

/**
 * A damped spring solved exactly over a step rather than integrated: {@code x'' = a - 2 zeta omega x' - omega^2 x}. The spring rests at {@code a / omega^2},
 * and the state after {@code dt} is the closed form of the motion toward it. It is stable at any frequency and any step, a constant force is taken exactly (the
 * spring settles at its sag, it does not creep toward it), and the frequency and damping ratio read as what they are: how fast it wobbles, how soon it settles
 */
public final class Oscillator {
	private Oscillator() {}

	/**
	 * One spring's step worked out ahead: its exponentials and its sine and cosine, which are most of what a step costs. A spring whose frequency and damping
	 * stay the same from step to step (most do) works them out once and steps any number of axes with them
	 */
	public static final class Coefficients {
		double omega = Double.NaN;
		double zeta = Double.NaN;
		double dt = Double.NaN;
		private boolean under;
		private boolean critical;
		private double decay;
		private double cos;
		private double sin;
		private double wd;
		private double zo;
		private double omega2;

		/** Works the step out for this frequency, damping and step, unless it already is */
		public Coefficients set(double omega, double zeta, double dt) {
			if (omega == this.omega && zeta == this.zeta && dt == this.dt)
				return this;

			this.omega = omega;
			this.zeta = zeta;
			this.dt = dt;
			this.under = omega >= 1e-6 && zeta < 1.0 - 1e-6;
			this.critical = omega >= 1e-6 && !this.under && zeta <= 1.0 + 1e-6;
			this.omega2 = omega * omega;

			if (this.critical)
				this.decay = Math.exp(-omega * dt);

			if (this.under) {
				this.wd = omega * Math.sqrt(1.0 - zeta * zeta);
				this.decay = Math.exp(-zeta * omega * dt);
				this.cos = Math.cos(this.wd * dt);
				this.sin = Math.sin(this.wd * dt);
				this.zo = zeta * omega;
			}

			return this;
		}

		/** {@link Oscillator#step} with these coefficients */
		public void step(double[] state, double a) {
			if (this.critical) {
				double rest = a / this.omega2;
				double y = state[0] - rest;
				double b = state[1] + this.omega * y;
				double moved = y + b * this.dt;

				state[0] = moved * this.decay + rest;
				state[1] = (b - this.omega * moved) * this.decay;

				return;
			}

			if (!this.under) {
				Oscillator.step(state, a, this.omega, this.zeta, this.dt);

				return;
			}

			double rest = a / this.omega2;
			double y = state[0] - rest;
			double b = (state[1] + this.zo * y) / this.wd;

			state[0] = this.decay * (y * this.cos + b * this.sin) + rest;
			state[1] = this.decay * ((b * this.wd - this.zo * y) * this.cos - (y * this.wd + this.zo * b) * this.sin);
		}
	}

	/**
	 * Steps one axis by {@code dt}: {@code state[0]} is the position and {@code state[1]} the velocity, both replaced
	 *
	 * @param a A constant acceleration over the step (the spring settles at {@code a / omega^2})
	 * @param omega The undamped frequency, radians a second
	 * @param zeta The damping ratio: below 1 it rings, 1 is critical, above 1 it creeps
	 */
	public static void step(double[] state, double a, double omega, double zeta, double dt) {
		double x = state[0];
		double v = state[1];

		if (omega < 1e-6) {
			// no spring: a body under a constant force
			state[0] = x + v * dt + 0.5 * a * dt * dt;
			state[1] = v + a * dt;

			return;
		}

		double rest = a / (omega * omega);
		double y = x - rest;

		if (zeta < 1.0 - 1e-6) {
			double wd = omega * Math.sqrt(1.0 - zeta * zeta);
			double decay = Math.exp(-zeta * omega * dt);
			double cos = Math.cos(wd * dt);
			double sin = Math.sin(wd * dt);
			double b = (v + zeta * omega * y) / wd;

			state[0] = decay * (y * cos + b * sin) + rest;
			state[1] = decay * ((b * wd - zeta * omega * y) * cos - (y * wd + zeta * omega * b) * sin);
		}
		else if (zeta <= 1.0 + 1e-6) {
			// critical: (y + (v + omega y) t) e^(-omega t)
			double decay = Math.exp(-omega * dt);
			double b = v + omega * y;

			state[0] = (y + b * dt) * decay + rest;
			state[1] = (b - omega * (y + b * dt)) * decay;
		}
		else {
			// over damped: two real decays
			double root = omega * Math.sqrt(zeta * zeta - 1.0);
			double r1 = -zeta * omega + root;
			double r2 = -zeta * omega - root;
			double c2 = (v - r1 * y) / (r2 - r1);
			double c1 = y - c2;
			double e1 = Math.exp(r1 * dt);
			double e2 = Math.exp(r2 * dt);

			state[0] = c1 * e1 + c2 * e2 + rest;
			state[1] = c1 * r1 * e1 + c2 * r2 * e2;
		}
	}
}
