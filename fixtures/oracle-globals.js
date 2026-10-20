// Minimal non-network browser globals, based on InnerTubeX QuickJsEngine.setupYoutubeGlobals.
// Deliberately no cipher implementation or error-to-identity fallback.
globalThis.window = globalThis;
globalThis.self = globalThis;
globalThis.document = Object.create(null);
globalThis.navigator = Object.create(null);
globalThis.XMLHttpRequest = { prototype: {} };
globalThis.location = {
    hash: "", host: "www.youtube.com", hostname: "www.youtube.com",
    href: "https://www.youtube.com/watch?v=yt-dlp-wins", origin: "https://www.youtube.com",
    password: "", pathname: "/watch", port: "", protocol: "https:",
    search: "?v=yt-dlp-wins", username: ""
};
if (typeof Intl === "undefined") {
    const NumberFormat = function(locale, options) { this.options = options || {}; };
    NumberFormat.supportedLocalesOf = function(locales) { return Array.isArray(locales) ? locales : [locales]; };
    NumberFormat.prototype.format = function(value) {
        return String(value).padStart(this.options.minimumIntegerDigits || 0, "0");
    };
    const DateTimeFormat = function() {};
    DateTimeFormat.prototype.resolvedOptions = function() { return { timeZone: "UTC" }; };
    DateTimeFormat.prototype.format = function(value) { return String(value); };
    globalThis.Intl = { NumberFormat, DateTimeFormat };
}
