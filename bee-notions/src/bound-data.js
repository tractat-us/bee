// Reads a Notions component's bound data straight from the surface's data model, and re-renders
// the component on every change at or below a bound path.
//
// Why not `controller.props`: web_core's binder hands a component a shallow copy of a bound
// object, while the data model updates nested values in place. A drop writes
// `/cards/items/c1/column`, and the copy's `items` is the very object that changed, so the binder
// sees no difference and never re-renders the Board. The data model's own `subscribe` fires for
// every change below a path, and `get` reads the value as it is now.

/** A Lit reactive controller giving `host` the current values of the paths it binds under `keys`. */
export class BoundData {
  constructor(host, keys) {
    this.host = host;
    this.keys = keys;
    this.watched = null;
    this.subscriptions = [];
    host.addController(this);
  }

  hostDisconnected() {
    this.unsubscribe();
  }

  /** Each key's current value: the data at its bound path, or the controller's prop when unbound. */
  values() {
    const model = this.host.context?.dataContext?.dataModel;
    const properties = this.host.context?.componentModel?.properties ?? {};
    const paths = this.keys.map((key) => (typeof properties[key]?.path === 'string' ? properties[key].path : null));
    const watched = paths.join('\n');
    if (model && this.host.isConnected && watched !== this.watched) {
      this.unsubscribe();
      this.watched = watched;
      this.subscriptions = paths.filter((path) => path !== null)
        .map((path) => model.subscribe(path, () => this.host.requestUpdate()));
    }
    return Object.fromEntries(this.keys.map((key, i) => [
      key,
      model && paths[i] !== null ? model.get(paths[i]) : this.host.controller?.props?.[key],
    ]));
  }

  unsubscribe() {
    for (const subscription of this.subscriptions) subscription.unsubscribe();
    this.subscriptions = [];
    this.watched = null;
  }
}
