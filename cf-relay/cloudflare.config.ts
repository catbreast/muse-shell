import { bindings, defineConfig, triggers } from "cf/config";
import * as entrypoint from "./src/index.js" with { type: "cf-worker" };

export default defineConfig({
	worker: {
		name: "phone-relay",
		compatibilityDate: "2026-10-06",
		entrypoint,
		triggers: [triggers.fetch({ pattern: "phone.ruisnow.de/*" })],
		exports: {
			Relay: { type: "durable-object", storage: "sqlite" },
		},
		env: {
			RELAY: bindings.durableObject({ worker: "phone-relay", exportName: "Relay" }),
			PHONE_TOKEN: bindings.secret(),
			ADMIN_TOKEN: bindings.secret(),
			BREENO_TOKEN: bindings.secret(),
		},
	},
});
