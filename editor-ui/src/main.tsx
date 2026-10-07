import { render } from "preact";
import { App } from "./App";
import "./styles.css";
import "./workspace/preview/recipe/recipePreviews.ts";

render(<App />, document.getElementById("app")!);
