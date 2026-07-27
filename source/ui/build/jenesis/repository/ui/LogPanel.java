package build.jenesis.repository.ui;

import build.jenesis.repository.store.ArtifactStore;

/**
 * The bundled recent-logs panel (WO.4): the console's window onto the server's {@code GET /api/logs} tail - the bounded
 * in-memory ring a logback appender feeds. Consistent with the enterprise console's Logs tab: a level filter, a text
 * search and an auto-tail (a poll that advances the {@code since} cursor), calling the key-auth'd JSON API with the
 * {@code Jenesis-Repository-Key} header (the free console authenticates the human by session, but the server's
 * {@code /api/logs} read is key-gated like every other {@code /api} surface, so the panel carries the key the same way
 * the enterprise console does). It reads nothing from the {@link ArtifactStore} - the log tail is a live API read, not
 * store state - and degrades gracefully: before a key is entered, or against a deployment whose ring is empty, it shows
 * an empty tail rather than an error.
 */
public final class LogPanel implements Panel {

    @Override
    public String id() {
        return "logs";
    }

    @Override
    public String title() {
        return "Logs";
    }

    @Override
    public String render(ArtifactStore store) {
        // A self-contained fragment: the shell drops it in unescaped. All dynamic text is API-derived and escaped in JS
        // before it reaches the DOM (jlogsEsc), so a log message cannot inject markup.
        return """
                <p>The instance's most recent log entries from the in-memory ring
                (<code>GET /api/logs</code>). The read is key-gated: paste a credential carrying
                <code>repository:read</code>. Level filters at that level or higher; search matches the logger or
                message; Tail polls for new entries past the last cursor.</p>
                <div class="grid">
                  <label>Level
                    <select id="jlogs-level">
                      <option value="">all</option><option>INFO</option><option>WARN</option><option>ERROR</option>
                    </select>
                  </label>
                  <label>Search <input id="jlogs-q" placeholder="text in logger or message"></label>
                  <label>Key <input id="jlogs-key" placeholder="tenant.secret" type="password"></label>
                </div>
                <p>
                  <button class="secondary" onclick="jlogsLoad(true)">Refresh</button>
                  <button class="secondary" id="jlogs-tail" onclick="jlogsToggle()">Start tail</button>
                  <span id="jlogs-status"></span>
                </p>
                <div id="jlogs-out"></div>
                <script>
                (function(){
                  var cursor=0, timer=null;
                  function esc(s){return (s==null?'':String(s)).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}
                  window.jlogsEsc=esc;
                  function params(tail){
                    var p=[]; var lv=document.getElementById('jlogs-level').value; if(lv)p.push('level='+encodeURIComponent(lv));
                    var q=document.getElementById('jlogs-q').value.trim(); if(q)p.push('q='+encodeURIComponent(q));
                    if(tail)p.push('since='+cursor); p.push('limit=200'); return p.join('&');
                  }
                  window.jlogsLoad=function(reset){
                    if(reset){cursor=0; document.getElementById('jlogs-out').innerHTML='';}
                    var key=document.getElementById('jlogs-key').value.trim();
                    var headers=key?{'Jenesis-Repository-Key':key}:{};
                    fetch('/api/logs?'+params(!reset),{headers:headers}).then(function(r){
                      if(!r.ok)throw new Error('status '+r.status); return r.json();
                    }).then(function(d){
                      cursor=d.cursor; render(d.entries,reset);
                      document.getElementById('jlogs-status').textContent=d.count+' shown, cursor '+d.cursor;
                    }).catch(function(e){document.getElementById('jlogs-status').textContent='error: '+e.message;});
                  };
                  function render(entries,reset){
                    var box=document.getElementById('jlogs-out');
                    if(reset&&(!entries||!entries.length)){box.innerHTML='<p>No entries.</p>';return;}
                    if(reset)box.innerHTML='<table id="jlogs-rows"><thead><tr><th>time</th><th>level</th><th>logger</th><th>message</th><th>tenant</th></tr></thead><tbody></tbody></table>';
                    var body=box.querySelector('#jlogs-rows tbody'); if(!body)return;
                    entries.forEach(function(e){
                      var tr=document.createElement('tr');
                      tr.innerHTML='<td>'+esc(e.timestamp)+'</td><td>'+esc(e.level)+'</td><td>'+esc(e.logger)+'</td><td>'+esc(e.message)+'</td><td>'+(e.tenant?esc(e.tenant):'-')+'</td>';
                      body.appendChild(tr);
                    });
                  }
                  window.jlogsToggle=function(){
                    var b=document.getElementById('jlogs-tail');
                    if(timer){clearInterval(timer);timer=null;b.textContent='Start tail';}
                    else{jlogsLoad(true);timer=setInterval(function(){jlogsLoad(false);},2000);b.textContent='Stop tail';}
                  };
                })();
                </script>
                """;
    }
}
