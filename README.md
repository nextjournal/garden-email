# Garden Email

A small helper library to send and receive email with application.garden. 

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
                           :body "Hello World!"})
```

## Receiving Email

You can process incoming email by adding the `nextjournal.garden-email/wrap-receive-email` middleware to your application and providing a callback:

```clojure {:nextjournal.clerk/code-listing true}
(defn on-receive [{:keys [to from subject content]}]
  (println (format "Received email to %s from %s with subject %s and content %s." to from subject content)))

(def wrapped-ring-handler (-> my-ring-handler (garden-email/wrap-receive-email on-receive)))
```

If you do not provide a custom callback, garden-email saves incoming email to a mailbox in persistent storage, which you can interact with using the following functions:

- `inbox`
- `save-to-inbox!`
- `delete-from-inbox!`
- `clear-inbox!`

## Development

When running your application locally in development, no actual emails are sent. Instead we collect mock-emails, which you can view at `/.application.garden/garden-email/outbox`, assuming you have added the ring middleware to your handler.

To mock incoming email, you can call `nextjournal.garden-email.mock/receive-email`.

## Mailbox

`nextjournal.garden-email.render` has helper functions to render a mailbox.

## Example

See `example` for an example application.
