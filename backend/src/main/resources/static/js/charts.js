// auth-core-mc — hand-rolled SVG chart generators for the admin metrics
// page (ticket 028). Zero external dependencies (same policy the project
// has held since ticket 009) — these are pure functions (data in, an SVG
// markup *string* out) rather than DOM-building functions, matching the
// existing pattern in fragments/icons.html (ticket 024) where every icon is
// plain literal SVG markup. A caller sets `.innerHTML` on a wrapper element
// with the returned string (see admin-metrics.html), same as any other
// server-rendered SVG fragment on these pages.
//
// Every color is one of admin.css's own custom properties
// (--admin-success-fg, --admin-error-fg, --admin-accent, --admin-border,
// --admin-fg, --admin-muted) — never a new hardcoded color — so these
// charts automatically stay in sync with the "Slate + Índigo" palette.
//
// Both charts are marked aria-hidden="true" in the SVG itself: they are a
// *visual* summary of numbers the page already renders as accessible text
// elsewhere (the stat cards, and the legend list built alongside each
// chart in admin-metrics.html) — same "decorative vs. accessible text"
// split fragments/icons.html already documents for its own icons.
const AuthCoreCharts = (() => {
  function escapeSvgText(value) {
    return String(value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
  }

  // SVG <text> doesn't wrap or ellipsize on its own — a long provider name
  // (e.g. a custom OIDC provider id) would otherwise run into the bar next
  // to it. The full, untruncated name is always still available in the
  // accessible legend list built next to the chart.
  function truncateLabel(value, maxLength) {
    const text = String(value);
    return text.length > maxLength ? text.slice(0, maxLength - 1) + "…" : text;
  }

  /**
   * Two-segment donut comparing successful vs. failed logins. Drawn as two
   * overlapping <circle> elements using the standard stroke-dasharray
   * technique (one full circle in the "failure" color as the base ring, a
   * second circle on top tracing only the "success" fraction of the
   * circumference) rather than <path> arc commands — two circles is enough
   * for exactly two segments and needs no trigonometry beyond the
   * circumference itself.
   *
   * Donut (not a bar) was chosen over a bar chart here specifically because
   * this pair is a *proportion of one whole* (every login is either a
   * success or a failure, never both) — a donut communicates "what share of
   * the whole" at a glance via the ring fill and the center label, which a
   * side-by-side bar pair doesn't as directly. The by-provider chart below
   * is a comparison across independent categories instead, which is what
   * bars communicate best — so the two charts on this page intentionally
   * use different forms for different questions, not the same form twice.
   */
  function successFailureDonut(successCount, failureCount) {
    const total = successCount + failureCount;
    const safeTotal = total > 0 ? total : 1;
    const successFraction = successCount / safeTotal;
    const radius = 45;
    const strokeWidth = 16;
    const circumference = 2 * Math.PI * radius;
    const successLength = circumference * successFraction;
    const remainderLength = circumference - successLength;
    const successPercent = Math.round(successFraction * 100);

    return (
      '<svg viewBox="0 0 120 120" role="img" aria-hidden="true" focusable="false">' +
      '<circle cx="60" cy="60" r="' +
      radius +
      '" fill="none" stroke="var(--admin-error-fg)" stroke-width="' +
      strokeWidth +
      '" />' +
      '<circle cx="60" cy="60" r="' +
      radius +
      '" fill="none" stroke="var(--admin-success-fg)" stroke-width="' +
      strokeWidth +
      '" stroke-dasharray="' +
      successLength.toFixed(2) +
      " " +
      remainderLength.toFixed(2) +
      '" stroke-dashoffset="0" transform="rotate(-90 60 60)" stroke-linecap="butt" />' +
      '<text x="60" y="57" text-anchor="middle" font-size="20" font-weight="700" fill="var(--admin-fg)">' +
      successPercent +
      "%</text>" +
      '<text x="60" y="74" text-anchor="middle" font-size="10" fill="var(--admin-muted)">éxito</text>' +
      "</svg>"
    );
  }

  /**
   * Horizontal bar chart, one row per identity provider. `entries` is an
   * array of `[providerName, count]` pairs — the caller decides the order
   * (admin-metrics.html sorts by count, descending) so the bars and the
   * accessible legend list built alongside them always agree.
   *
   * Horizontal (not vertical) bars were chosen because provider names are
   * text labels of unpredictable length ("google", "facebook", a future
   * custom OIDC provider id) — a label reads naturally to the left of a
   * horizontal bar, where a vertical bar would need to rotate or wrap the
   * label underneath it.
   *
   * Caller contract: only call this with 2 or more entries (see
   * admin-metrics.html's renderProviderChart) — a single entry is shown as
   * a plain sentence instead, since a "comparison" chart with one thing to
   * compare isn't a comparison. `entries` must be non-empty.
   */
  function providerBarChart(entries) {
    const rowHeight = 30;
    const barHeight = 14;
    const labelWidth = 100;
    const barAreaWidth = 150;
    const valueGap = 8;
    const valueWidth = 32;
    const viewBoxWidth = labelWidth + barAreaWidth + valueGap + valueWidth;
    const viewBoxHeight = entries.length * rowHeight;
    const maxCount = Math.max(...entries.map(([, count]) => count));

    const rows = entries
      .map(([provider, count], index) => {
        const rowY = index * rowHeight;
        const textY = rowY + rowHeight / 2 + 4;
        const barY = rowY + (rowHeight - barHeight) / 2;
        const barWidth = maxCount > 0 ? Math.max(2, (count / maxCount) * barAreaWidth) : 2;
        const label = escapeSvgText(truncateLabel(provider, 14));

        return (
          '<text x="0" y="' +
          textY +
          '" font-size="11" fill="var(--admin-fg)">' +
          label +
          "</text>" +
          '<rect x="' +
          labelWidth +
          '" y="' +
          barY +
          '" width="' +
          barWidth.toFixed(2) +
          '" height="' +
          barHeight +
          '" rx="3" fill="var(--admin-accent)" />' +
          '<text x="' +
          (labelWidth + barAreaWidth + valueGap) +
          '" y="' +
          textY +
          '" font-size="11" fill="var(--admin-muted)">' +
          escapeSvgText(count) +
          "</text>"
        );
      })
      .join("");

    return (
      '<svg viewBox="0 0 ' +
      viewBoxWidth +
      " " +
      viewBoxHeight +
      '" role="img" aria-hidden="true" focusable="false">' +
      rows +
      "</svg>"
    );
  }

  return {
    successFailureDonut,
    providerBarChart,
  };
})();
