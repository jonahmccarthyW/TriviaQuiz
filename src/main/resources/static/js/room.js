/*
 * The networked game's client. Both the host screen and the players' phones run this: they poll
 * /api/room/{code}/state and redraw from whatever comes back. The server decides what each device
 * is allowed to see, so the only difference between a phone and the host screen here is that the
 * host is never "you" and so never gets answer buttons.
 *
 * Everything is built with textContent rather than innerHTML — questions come from opentdb with
 * HTML entities already decoded, so they can contain markup that must not be executed.
 */
(function (global) {
  'use strict';

  var POLL_MS = 1000;

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) { node.className = className; }
    if (text !== undefined && text !== null) { node.textContent = text; }
    return node;
  }

  function startRoom(options) {
    var code = options.code;
    var mount = options.mount;
    var drawnVersion = -1;
    var wasYourTurn = false;
    var stopped = false;
    var timer = null;

    function stop(message) {
      stopped = true;
      if (timer) { clearTimeout(timer); }
      mount.textContent = '';
      mount.appendChild(el('p', 'room-status', message));
    }

    function schedule() {
      if (stopped) { return; }
      timer = setTimeout(poll, POLL_MS);
    }

    function poll() {
      if (stopped) { return; }
      fetch('/api/room/' + code + '/state', { headers: { 'Accept': 'application/json' } })
        .then(function (response) {
          if (response.status === 404) {
            stop('This room has closed.');
            return null;
          }
          return response.json();
        })
        .then(function (state) {
          if (state) { render(state); }
          schedule();
        })
        // A failed fetch is usually the server restarting or the phone's Wi-Fi blinking.
        // Keep polling; the next one will pick the game back up.
        .catch(schedule);
    }

    function submit(state, answer) {
      fetch('/api/room/' + code + '/answer', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ version: state.version, answer: answer })
      })
        .then(function (response) { return response.json(); })
        // A 409 body is the current state too, so a stale tap just re-syncs the screen.
        .then(function (next) { if (next) { render(next); } })
        .catch(function () { /* the next poll will catch up */ });
    }

    function renderPlayers(state) {
      if (!state.players.length) { return; }
      var list = el('div', 'roster');
      state.players.forEach(function (player) {
        var row = el('div', 'player' + (player.current ? ' current' : ''));
        var label = player.name + (player.you ? ' (you)' : '');
        row.appendChild(el('span', null, label));
        if (state.phase !== 'lobby') {
          row.appendChild(el('span', 'player-cats', player.cleared + ' / ' + player.total + ' cleared'));
        }
        list.appendChild(row);
      });
      mount.appendChild(list);
    }

    function renderQuestion(state) {
      var q = state.question;
      var card = el('fieldset');
      card.appendChild(el('legend', null, q.category + ' (' + q.difficulty + ')'));
      card.appendChild(el('p', 'question-meta',
        'Question ' + q.step + ' of ' + q.steps + ' this turn'));
      card.appendChild(el('p', null, q.question));

      q.answers.forEach(function (answer) {
        if (state.yourTurn) {
          var button = el('button', 'answer', answer);
          button.type = 'button';
          button.addEventListener('click', function () {
            // Disable the whole set immediately: on a phone it is easy to double-tap, and the
            // server would reject the second one as stale anyway.
            Array.prototype.forEach.call(card.querySelectorAll('button'), function (b) {
              b.disabled = true;
            });
            submit(state, answer);
          });
          card.appendChild(button);
        } else {
          card.appendChild(el('div', 'answer readonly', answer));
        }
      });

      mount.appendChild(card);
    }

    function renderFeedback(state) {
      var f = state.feedback;
      var banner = el('div', 'banner ' + (f.correct ? 'ok' : 'bad'));
      banner.appendChild(el('strong', null,
        f.answeredBy + (f.correct ? ' got it right' : ' got it wrong')));
      banner.appendChild(el('p', null, f.question));
      if (!f.correct) {
        banner.appendChild(el('p', null,
          (f.givenAnswer ? 'Answered: ' + f.givenAnswer + ' — ' : '') +
          'the answer was ' + f.correctAnswer));
      }
      mount.appendChild(banner);
    }

    function statusLine(state) {
      if (state.phase === 'lobby') {
        if (!state.players.length) { return 'Waiting for players to join...'; }
        return state.canStart
          ? 'Ready when the host starts the game.'
          : 'Waiting for more players...';
      }
      if (state.phase === 'finished') {
        return state.winner + ' wins!';
      }
      var current = state.players.filter(function (p) { return p.current; })[0];
      // Between questions the server is off fetching the next one, which can take a few seconds
      // when opentdb is rate-limiting us. Say so rather than showing an empty screen.
      if (!state.question && !state.feedback) {
        return current ? 'Getting a question for ' + current.name + '...' : 'Getting a question...';
      }
      if (state.yourTurn) { return 'Your turn'; }
      return current ? current.name + "'s turn" : 'Playing...';
    }

    function render(state) {
      if (state.version === drawnVersion) { return; }
      drawnVersion = state.version;

      // A short buzz when the turn lands on this phone — it may be in someone's pocket.
      if (state.yourTurn && !wasYourTurn && global.navigator && global.navigator.vibrate) {
        global.navigator.vibrate(200);
      }
      wasYourTurn = state.yourTurn;

      mount.textContent = '';
      mount.appendChild(el('h2', 'room-status', statusLine(state)));
      if (state.notice) { mount.appendChild(el('p', 'error', state.notice)); }
      renderPlayers(state);
      if (state.feedback) { renderFeedback(state); }
      if (state.question) { renderQuestion(state); }
    }

    poll();
  }

  global.startRoom = startRoom;
})(window);
