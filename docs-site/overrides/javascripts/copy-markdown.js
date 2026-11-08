// "Copy page as Markdown": fetch the page's Markdown copy and put it on the clipboard.
// One delegated listener, so it keeps working after navigation.instant swaps the page.
//
// Safari and iOS drop the user gesture across an await (WebKit bug 244812), so a
// writeText after the fetch fails there with NotAllowedError. Where ClipboardItem
// exists, the handler calls clipboard.write synchronously and hands it a promise
// of the text; the fetch-then-writeText path is only the fallback.

function markdownBlob(url) {
  return fetch(url)
    .then((response) => {
      if (!response.ok) throw new Error(`${response.status} ${response.statusText}`)
      return response.text()
    })
    .then((text) => new Blob([text], { type: "text/plain" }))
}

function copyMarkdown(url) {
  if (typeof ClipboardItem !== "undefined" && navigator.clipboard?.write) {
    return navigator.clipboard.write([new ClipboardItem({ "text/plain": markdownBlob(url) })])
  }
  return markdownBlob(url)
    .then((blob) => blob.text())
    .then((text) => navigator.clipboard.writeText(text))
}

// Swap the icon for about two seconds: a tick on success, a warning on failure.
const timers = new WeakMap()
function show(button, state, label) {
  clearTimeout(timers.get(button))
  button.dataset.state = state
  button.setAttribute("aria-label", label)
  timers.set(button, setTimeout(() => {
    delete button.dataset.state
    button.setAttribute("aria-label", "Copy page as Markdown")
  }, 2000))
}

document.addEventListener("click", (event) => {
  const button = event.target.closest(".bee-copy-markdown")
  if (!button) return
  event.preventDefault()
  copyMarkdown(button.dataset.markdown).then(
    () => show(button, "done", "Copied"),
    (error) => {
      console.error("Copy page as Markdown failed:", error)
      show(button, "failed", "Copy failed")
    },
  )
})
