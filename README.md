# Garden Email

A small helper library to send and receive email with application.garden. 

## Installation

Garden Email is [hosted on GitHub](https://github.com/nextjournal/garden-email) so you can simply add it as a git dependency to your `deps.edn`:

```clojure {:nextjournal.clerk/code-listing true}
{io.github.nextjournal/garden-email {:git/sha "<latest-sha>"}}
```

## My Email Address

Your own email address is available in `nextjournal.garden-email/my-email-address`.
You can send email from this address, including plus-addresses and receive email at this address, including plus addresses.

There is a helper function to construct plus addresses:

```clojure {:nextjournal.clerk/code-listing true}
(garden-email/plus-address "foo@example.com" "bar")
; => "foo+bar@example.com"
```

## Sending Email

You can send email using `nextjournal.garden-email/send-email!`:

```clojure {:nextjournal.clerk/code-listing true}
(garden-email/send-email! {:to {:email "foo@example.com"
                                :name "Foo Bar"}
                           :from {:email garden-email/my-email-address
                                  :name "My App"}
                           :subject "Hi!"
                           :text "Hello World!"
                           :html "<html><body><h1>Hello World!</h1></body></html>"})
```

Every parameter except for `{:to {:email "…"}}` is optional.

### Double-Opt-In

The first time you send an email to a new email address, the recipient gets a generic email to confirm that they want to receive your email.
Your original email gets buffered and sent to the recipient, as soon as they confirm that they want your email.

You are blocked from sending more email to that address, until the recipient confirms that they want your email.

After the recipient confirmed they want to receive further email, you can continue sending email as usual.
application.garden automatically adds a footer to unsubscribe from future emails to every email.


## Receiving Email

You can process incoming email by adding the `nextjournal.garden-email/wrap-with-email` middleware to your application and providing a callback:

```clojure {:nextjournal.clerk/code-listing true}
(defn on-receive [{:keys [message-id from to reply-to subject text html]}]
  (println (format "Received email from %s to %s with subject %s." from to subject)))

(def wrapped-ring-handler
  (-> my-ring-handler (garden-email/wrap-with-email {:on-receive on-receive})))
```

If you do not provide a custom callback, garden-email saves incoming email to a mailbox in persistent storage, which you can interact with using the following functions:

- `inbox`
- `save-to-inbox!`
- `delete-from-inbox!`
- `clear-inbox!`

## Development

When running your application locally in development, no actual emails are sent.
Instead we collect mock-emails, which you can view at the url in `nextjournal.garden-email/outbox-url`,
assuming you have added the ring middleware to your handler.

To mock incoming email, you can call `nextjournal.garden-email/receive-email`.

## Mailbox

`nextjournal.garden-email.render` has helper functions to render a mailbox.

## Example

See [example](https://github.com/nextjournal/garden-email/tree/main/example) for an example application.
