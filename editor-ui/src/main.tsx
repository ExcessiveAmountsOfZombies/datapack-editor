import { render } from "preact";
import { App } from "./App";
import "./styles.css";
import "./workspace/preview/recipePreviews";

render(<App />, document.getElementById("app")!);
