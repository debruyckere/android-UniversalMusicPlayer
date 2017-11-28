document.querySelectorAll('h1, h2, h3, h4, h5, p, li').forEach(function(el) {
    var p1 = el.parentNode;
    var p2 = p1.parentNode;
    if (el.className.indexOf('vrt-title') != -1 ||
        p1.className.indexOf('article__intro') != -1 || p2.className.indexOf('article__intro') != -1 ||
        p1.className.indexOf('parbase') != -1 || p2.className.indexOf('parbase') != -1) {
        if (el.innerHTML.indexOf('Lees verder onder') == -1) {
          window.ContentScraper.content(el.innerHTML, "");
        }
    }
} );
window.ContentScraper.finished();