# Garden Email

A small helper library to send and receive email with application.garden. 

## Sending Email

You can send email using `send-email!`:

``` clojure
(garden-email/send-email! {:to {:email "foo@example.com"
                                :name "Foo Bar"}
                           :subject "Hi!"
                           :body "Hello World!"})
```

## Receiving Email

You can process incoming email by adding the `wrap-receive-email` middleware to your application and providing a callback:

``` clojure
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

When running your application locally in development, no actual emails are sent. Instead we collect mock-emails, which you can view at `/.application.garden/outbox`

## Example

See `example` for an example application
