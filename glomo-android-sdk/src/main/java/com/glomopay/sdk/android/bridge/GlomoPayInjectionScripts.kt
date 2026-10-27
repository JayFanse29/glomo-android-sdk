package com.glomopay.sdk.android.bridge

import com.glomopay.sdk.android.ResolvedColorScheme

/** Payment-event bridge script mirrored from the Flutter InjectionScripts. */
internal object GlomoPayInjectionScripts {
    fun main(bridgeName: String = "GlomoPayBridge"): String = build(bridgeName)

    /**
     * Hands the resolved colour scheme to the page.
     *
     * Parks the value on `window.__glomoColorScheme__` *before* calling the setter, every time.
     * The page defines the setter when its app mounts, which can be later than the document
     * finishing, so a scheme pushed into that window would be lost with nothing to retry it. The
     * parked value is what the page seeds from on mount. Before, not after: a setter already
     * present runs synchronously and would read a global not yet written.
     *
     * Nothing reads the global today - that half is the checkout page's and lands with it, which
     * is what lets the two sides ship independently.
     *
     * Guards the *function*, not `window`: in a WebView `window` always exists and it is the
     * setter that is absent - during load, or on a merchant deploy that predates it - so
     * `window?.glomoSetColorScheme(...)` would throw where this skips.
     *
     * Deliberately not idempotent, unlike the injections around it. This one is re-run on every
     * page load and on every scheme change.
     */
    fun colorScheme(resolved: ResolvedColorScheme): String = """
        (function() {
          try {
            window.__glomoColorScheme__ = '${resolved.wireName}';
            if (typeof window.glomoSetColorScheme === 'function') {
              window.glomoSetColorScheme('${resolved.wireName}');
            }
          } catch (e) {}
        })();
    """.trimIndent()

    fun flow(bridgeName: String = "GlomoPayFlowBridge"): String = build(bridgeName) + """
        (function() {
          if (!window.opener) {
            window.opener = { postMessage: function(data) {
              try {
                if (typeof data === 'string') data = JSON.parse(data);
                window.$bridgeName.postMessage(JSON.stringify({type:'message',data:data}));
              } catch(e) {}
            }};
          }
        })();
    """.trimIndent()

    fun bankViewportFit(): String = """
    (function() {
      if (window.__glomoViewportFitFixApplied__) return;
      window.__glomoViewportFitFixApplied__ = true;

      function ensureViewportMeta() {
        var head = document.head || document.getElementsByTagName('head')[0];
        if (!head) return;

        var meta = document.querySelector('meta[name="viewport"]');
        if (!meta) {
          meta = document.createElement('meta');
          meta.name = 'viewport';
          head.appendChild(meta);
        }

        meta.setAttribute(
          'content',
          'width=device-width, initial-scale=1, maximum-scale=1, minimum-scale=1, user-scalable=no, viewport-fit=cover'
        );
      }

      function normalizeZoom() {
        try { document.documentElement.style.zoom = '1'; } catch (e) {}
        try { document.body.style.zoom = '1'; } catch (e) {}
      }

      var resetTimer = null;
      function scheduleNormalize() {
        if (resetTimer) clearTimeout(resetTimer);
        resetTimer = setTimeout(function() {
          ensureViewportMeta();
          normalizeZoom();
        }, 50);
      }

      ensureViewportMeta();
      normalizeZoom();

      window.addEventListener('load', function() {
        scheduleNormalize();
      });

      // Deliberately unguarded, unlike the visualViewport listener below.
      //
      // A width-only guard was tried here and reverted.
      // Two reasons. First, the
      // premise was unverified: the justification was that WKWebView fires
      // window.resize when the keyboard opens, but the software keyboard shrinks
      // the VISUAL viewport, not the layout viewport - which is why
      // iosKeyboardLayoutFix can capture window.innerHeight once and treat it as
      // a fixed baseline. If that holds, the guard never fires on the keyboard
      // path and fixes nothing. Second, this script is injected on the flow
      // WebView without a platform check, so any change here lands on Android
      // bank pages too, and that is only verifiable by completing real payments
      // across every bank and order type.
      //
      // What it costs to leave unguarded: keyboard open/close re-runs
      // scheduleNormalize, which rewrites the page's own viewport meta and
      // writes documentElement/body zoom. That is also the only thing that
      // re-asserts the forced viewport after a bank page overwrites its own meta
      // mid-flow, so the unguarded version is risky for SPA bank flows.
      window.addEventListener('resize', function() {
        scheduleNormalize();
      });

      if (window.visualViewport) {
        var lastVVWidth = window.visualViewport.width;
        window.visualViewport.addEventListener('resize', function() {
          var newWidth = window.visualViewport.width;
          if (Math.abs(newWidth - lastVVWidth) > 1) {
            lastVVWidth = newWidth;
            scheduleNormalize();
          }
        });
      }

      document.addEventListener('orientationchange', function() {
        scheduleNormalize();
      }, true);
    })();
      """.trimIndent()

    /**
     * Forwards the education page's one signal, `{type:'lrs.has_education_steps', value:true}`.
     * The page never emits `value:false`: no signal means no content, so nothing else is forwarded
     * and there is no DOM heuristic. A signal sent before the bridge exists is held and flushed
     * the next time this script runs (document start, page start, page finish).
     */
    fun carousel(bridgeName: String = "GlomoCarousel"): String = """
        (function() {
          var pendingKey = '__glomoCarouselPendingMessage__';
          var post = function(payload) {
            try {
              if (!window.$bridgeName) return false;
              window.$bridgeName.postMessage(payload);
              return true;
            } catch(e) { return false; }
          };
          var send = function(data) {
            try {
              var parsed = typeof data === 'string' ? JSON.parse(data) : data;
              if (!parsed || parsed.type !== 'lrs.has_education_steps' || parsed.value !== true) return;
              // One signal per page: the postMessage wrapper and the message listener both see it.
              if (window.__glomoCarouselSignalSeen__) return;
              window.__glomoCarouselSignalSeen__ = true;
              var payload = JSON.stringify({type:'lrs.has_education_steps',value:true});
              if (!post(payload)) window[pendingKey] = payload;
            } catch(e) {}
          };

          if (!window.__glomoCarouselListenerReady__) {
            window.__glomoCarouselListenerReady__ = true;
            try {
              var originalPostMessage = window.postMessage.bind(window);
              window.postMessage = function(message, targetOrigin, transfer) {
                send(message);
                return originalPostMessage(message, targetOrigin, transfer);
              };
            } catch(e) {}
            window.addEventListener('message', function(event) {
              if (event.data) send(event.data);
            });
          }

          if (window[pendingKey] && post(window[pendingKey])) window[pendingKey] = null;
        })();
    """.trimIndent()

    private fun build(bridgeName: String): String = """
        (function() {
          var flag = '__glomo_${bridgeName}_Injected__';
          if (window[flag]) return;
          window[flag] = true;
          // The bridge's own call is the only thing guarded. If the native peer is gone,
          // postMessage throws; unguarded, that reached the page's error listener below, which
          // called bridge() again. The failure is recorded once per page instead: a window
          // marker always, and a console warning in internal builds only, since WebView forwards
          // page console output to logcat and release builds log nothing. Errors the page raises
          // still reach the listener and are reported one-for-one, as before.
          var failedFlag = '__glomo_${bridgeName}_Failed__';
          var nativeWarn = (function() {
            try { return console.warn.bind(console); } catch(e) { return function() {}; }
          })();
          var inBridge = false;
          var bridge = function(msg) {
            // Anything the peer triggers synchronously from inside its own postMessage, such
            // as an error event, must not call back into it.
            if (inBridge) return;
            inBridge = true;
            try {
              if (window.$bridgeName) window.$bridgeName.postMessage(msg);
            } catch(e) {
              if (!window[failedFlag]) {
                window[failedFlag] = true;
                if (window.__glomoDevMode__ === true) {
                  try { nativeWarn('[GlomoPay] $bridgeName.postMessage failed: ' + e); } catch(ignored) {}
                }
              }
            } finally {
              inBridge = false;
            }
          };
          var dev = function() { return window.__glomoDevMode__ === true; };
          bridge(JSON.stringify({type:'console',level:'info',message:'GlomoPay Injection Loaded ($bridgeName)'}));

          var oldLog = console.log, oldWarn = console.warn;
          var oldError = console.error, oldInfo = console.info;
          var sendLog = function(level, message) {
            if (dev()) bridge(JSON.stringify({type:'console',level:level,message:String(message)}));
          };
          console.log = function(m) { oldLog(m); sendLog('log', m); };
          console.warn = function(m) { oldWarn(m); sendLog('warn', m); };
          console.error = function(m) { oldError(m); sendLog('error', m); };
          console.info = function(m) { oldInfo(m); sendLog('info', m); };

          window.open = function(url) {
            if (url) bridge(JSON.stringify({type:'window.open',url:String(url)}));
            return {
              close:function(){ bridge(JSON.stringify({type:'window.close'})); },
              focus:function(){}, blur:function(){}, postMessage:function(){},
              location: {
                get href(){ return url || ''; },
                set href(v){ bridge(JSON.stringify({type:'window.open',url:String(v)})); },
                assign:function(v){ bridge(JSON.stringify({type:'window.open',url:String(v)})); },
                replace:function(v){ bridge(JSON.stringify({type:'window.open',url:String(v)})); }
              }
            };
          };
          var oldClose = window.close;
          window.close = function() {
            bridge(JSON.stringify({type:'window.close'}));
            try { oldClose(); } catch(e) {}
          };

          try {
            var oldSubmit = HTMLFormElement.prototype.submit;
            HTMLFormElement.prototype.submit = function() {
              if (this.target === '_blank') this.target = '_self';
              return oldSubmit.call(this);
            };
          } catch(e) {}

          // Mirror Flutter's dev-only network diagnostics without inspecting
          // response bodies for payment decisions.
          var originalFetch = window.fetch;
          if (originalFetch) {
            window.fetch = function() {
              var args = arguments;
              var requestUrl = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url) || '';
              var noisy = requestUrl.indexOf('.lottie') >= 0 || requestUrl.indexOf('.wasm') >= 0;
              if (dev() && !noisy) sendLog('info', 'Fetch Start: ' + requestUrl);
              return originalFetch.apply(this, args).then(function(response) {
                if (dev() && !noisy) sendLog('info', 'Fetch Complete: ' + requestUrl + ' | HTTP ' + response.status);
                return response;
              }).catch(function(error) {
                if (dev() && !noisy) sendLog('error', 'Fetch Error: ' + error);
                throw error;
              });
            };
          }

          var originalXhrOpen = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function(method, url) {
            this.__glomoUrl = url;
            this.__glomoMethod = method;
            if (dev() && url && url.indexOf('.lottie') < 0 && url.indexOf('.wasm') < 0) {
              sendLog('info', 'XHR Start: ' + method + ' ' + url);
            }
            return originalXhrOpen.apply(this, arguments);
          };

          window.addEventListener('error', function(e) {
            bridge(JSON.stringify({type:'webview.error',errorType:'js_error',message:String(e.message||'JavaScript error')}));
            sendLog('error', 'Uncaught: ' + e.message);
          });
          window.addEventListener('unhandledrejection', function(e) {
            bridge(JSON.stringify({type:'webview.error',errorType:'unhandled_rejection',message:String(e.reason||'Unhandled promise rejection')}));
            sendLog('error', 'Unhandled Rejection: ' + e.reason);
          });
          window.addEventListener('message', function(e) {
            if (e.data) bridge(JSON.stringify({type:'message',data:e.data}));
          });
          document.addEventListener('click', function(e) {
            var t = e.target;
            if (t && t.tagName === 'INPUT' && t.type === 'file') {
              bridge(JSON.stringify({type:'file.input',accept:t.getAttribute('accept')||'',
                capture:t.getAttribute('capture')||'',inputId:t.id||'',inputName:t.name||''}));
            }
          }, true);
          var sendBridgeReady = function() {
            if (window.__glomoBridgeReadySent__) return;
            try {
              if (window.top !== window) return;
            } catch (e) {
              return;
            }
            window.__glomoBridgeReadySent__ = true;
            bridge(JSON.stringify({type:'bridge.ready'}));
          };
          if (document.readyState === 'complete') {
            sendBridgeReady();
          } else {
            window.addEventListener('load', sendBridgeReady, {once:true});
          }
        })();
    """.trimIndent()
}
