// Moments are CONTENT only: which screenshot each device shows and the default copy.
// They never change the layout (frames, crops, visibility, text block).

export const SCREENS = {
  "01-home": "Home",
  "02-chat-code": "Code",
  "03-chat-permission": "Approve",
  "04-changes": "Changes",
  "05-settings": "Settings",
};

// Where the meaningful content sits on each screenshot: [x0, x1, y0, y1] as screen fractions.
// Used for tablet focus (zoom the content column, aim it at the visible window).
export const CONTENT = {
  "iphone-01-home": [0, 1, 0.07, 0.52],
  "iphone-02-chat-code": [0, 1, 0.12, 0.58],
  "iphone-03-chat-permission": [0, 1, 0.555, 0.968],
  "iphone-04-changes": [0, 1, 0.075, 0.53],
  "iphone-05-settings": [0, 1, 0.07, 0.75],
  "android-01-home": [0, 1, 0.07, 0.5],
  "android-02-chat-code": [0, 1, 0.245, 0.79],
  "android-03-chat-permission": [0, 1, 0.56, 0.97],
  "android-04-changes": [0, 1, 0.07, 0.5],
  "android-05-settings": [0, 1, 0.07, 0.75],
  "ipad-01-home": [0, 1, 0, 0.5],
  "ipad-02-chat-code": [0.16, 0.84, 0.39, 0.83],
  "ipad-03-chat-permission": [0.16, 0.84, 0.69, 0.975],
  "ipad-04-changes": [0.15, 0.85, 0, 0.36],
  "ipad-05-settings": [0.1, 0.9, 0, 0.6],
  "tablet-01-home": [0, 1, 0, 0.5],
  "tablet-02-chat-code": [0.03, 0.97, 0.3, 0.83],
  "tablet-03-chat-permission": [0.03, 0.97, 0.68, 0.975],
  "tablet-04-changes": [0.02, 0.98, 0, 0.36],
  "tablet-05-settings": [0.02, 0.98, 0, 0.6],
};

// shot: main phone(s) · alt: a second phone where a composition has one
// tab: tablet next to phones · solo: tablet on its own
export const MOMENTS = {
  approval: {
    name: "Approval",
    shot: "03-chat-permission",
    alt: "02-chat-code",
    tab: "02-chat-code",
    solo: "03-chat-permission",
    copy: {
      label: "Permission requested",
      headline: "Approve the push\nfrom anywhere.",
      subline: "Your agent asks before it acts. Allow once or deny, right from your phone.",
    },
  },
  code: {
    name: "Code",
    shot: "02-chat-code",
    alt: "04-changes",
    tab: "02-chat-code",
    solo: "02-chat-code",
    copy: {
      label: "Live session chat",
      headline: "Read every line\nit writes.",
      subline: "Markdown and highlighted code, streamed live from your own opencode server.",
    },
  },
  sessions: {
    name: "Sessions",
    shot: "01-home",
    alt: "02-chat-code",
    tab: "01-home",
    solo: "01-home",
    copy: {
      label: "Sessions",
      headline: "Every session,\nat a glance.",
      subline: "See what is running and what is idle across your projects, live.",
    },
  },
  changes: {
    name: "Changes",
    shot: "04-changes",
    alt: "02-chat-code",
    tab: "04-changes",
    solo: "04-changes",
    copy: {
      label: "Git changes",
      headline: "Review the diff\nbefore it ships.",
      subline: "Every changed file with its additions and deletions, one tap away.",
    },
  },
};

export const SHARED_COPY = {
  brand: "OpenCode Mobile",
  appStoreLine: "Download on the",
  appStore: "App Store",
  googlePlayLine: "Get it on",
  googlePlay: "Google Play",
  disclaimer: "Unofficial client · connects to your own opencode server",
};

export const MOMENT_COPY_KEYS = ["label", "headline", "subline"];
export const COPY_KEYS = [...MOMENT_COPY_KEYS, ...Object.keys(SHARED_COPY)];

export const shotFor = (momentId, role) => {
  const m = MOMENTS[momentId];
  return m[role] || m.shot;
};

/** Copy for a moment: moment defaults, then the spec's overrides. */
export function resolveCopy(specCopy, momentId) {
  return { ...SHARED_COPY, ...MOMENTS[momentId].copy, ...(specCopy || {}) };
}
