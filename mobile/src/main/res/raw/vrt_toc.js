function findAncestor(el, cls) {
  while ((el = el.parentElement) && el !== null && !el.classList.contains(cls));
  return el;
}

document.querySelectorAll('h2.vrt-teaser__title').forEach(function(el) {
  window.ContentScraper.content(el.innerHTML, findAncestor(el, 'vrt-teaser').href);
});

window.ContentScraper.finished();