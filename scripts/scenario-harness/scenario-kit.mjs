// Shared building blocks for scenario files.

export const shell = { v1: "bash", v2: "shell" };
export const subagentTool = { v1: "task", v2: "subagent" };
// A file argument: v1 tools take an absolute filePath, v2 tools a project-relative path.
export const path = (rel) => ({ v1: { filePath: `\${PROJECT}/${rel}` }, v2: { path: rel } });
export const say = (value, chunkChars = 10, delayMs = 30) => ({ op: "text", value, chunkChars, delayMs });
export const run = (command, description) => ({ op: "tool", name: shell, input: { command, description } });
// Subagent input: v1 names the agent subagent_type, v2 names it agent.
export const subagent = (description, prompt, extra = {}) => ({
  v1: { description, prompt, subagent_type: "general", ...extra },
  v2: { description, prompt, agent: "general", ...extra },
});

export const CLEAN_DIST = [
  { steps: [say("I'll remove the stale `dist/` folder."), run("rm -rf dist", "Remove stale build output")] },
  { steps: [say("Removed `dist/`. Run `npm run build` to regenerate it.")] },
];

export const CURRENCY_QUESTION = {
  header: "Currency source",
  question: "Where should formatMoney get the currency from?",
  multiple: false,
  options: [
    { label: "Argument", description: "Pass a currency code on every call" },
    { label: "Invoice field", description: "Read invoice.currency, default USD" },
    { label: "Env config", description: "Use ACME_CURRENCY for the whole app" },
  ],
};

// Answers questions on either protocol: v1 question.asked, v2 form.created.
export const QUESTION_EVENTS = ["question.asked", "form.created"];
