import "../App.css";
import React, { useState } from "react";
import {
  LinkOutlined,
  SettingOutlined,
  LogoutOutlined,
  DatabaseOutlined,
  ApiOutlined,
  ClusterOutlined,
  AppstoreOutlined,
} from "@ant-design/icons";
import { Menu, message } from "antd";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../utils/auth";
import { get } from "react-hook-form";

function getItem(label, key, icon, children, disabled = false) {
  return {
    key,
    icon,
    children,
    label,
    disabled,
  };
}

const items = [
  getItem("Dashboard", "0", <AppstoreOutlined />),
  getItem("Drivers", "1", <ApiOutlined />, [
    getItem("MQTT", "11"),
    getItem("OPC UA", "12"),
    getItem("PLC4X", "13 ", null, null, true),
  ]),
  getItem("Servers", "2", <ClusterOutlined />, [
    getItem("MQTT", "21"),
    getItem("GraphQL", "22"),
    getItem("OPC UA", "23"),
  ]),
  getItem("Loggers", "3", <DatabaseOutlined />, [
    getItem("MQTT", "31"),
    getItem("InfluxDB", "32"),
    getItem("IoTDB", "33", null, null, true),
    getItem("Kafka", "34", null, null, true),
    getItem("JDBC", "35"),
    getItem("QuestDB", "36", null, null, true),
    getItem("Neo4J", "37", null, null, true),
  ]),
  getItem(
    <a
      href="https://github.com/vogler75/automation-gateway"
      target="_blank"
      rel="noopener noreferrer"
    >
      Documentation
    </a>,
    "link",
    <LinkOutlined />
  ),
  getItem("Settings", "4", <SettingOutlined />),
  getItem("Logout", "5", <LogoutOutlined />),
];

export function LeftSideNav() {
  const auth = useAuth();
  const navigate = useNavigate();
  const [mode, setMode] = useState("inline");
  const [theme, setTheme] = useState("light");

  const changeMode = (value) => {
    setMode(value ? "vertical" : "inline");
  };

  const changeTheme = (value) => {
    setTheme(value ? "dark" : "light");
  };

  // Function to handle logout mutation
  const logoutMutation = async () => {
    const mutation = `mutation {
      deleteAccessToken(token: "${auth.token.replace(/["']/g, "")}") {
        ok
        code
        message
      }
    }`;

    console.log("Logout mutation query:", mutation); // Log the mutation query

    try {
      const response = await fetch("http://localhost:9999/admin/api", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ query: mutation }),
      });

      const data = await response.json();
      console.log("Logout mutation response:", data); // Log the response from the API

      if (data.errors) {
        message.error(data.errors[0].message);
      } else {
        message.success("Logout successful!");
        auth.logout(); // Call the logout function from useAuth
        navigate("/"); // Redirect after successful logout
      }
    } catch (error) {
      console.error("Error sending logout mutation:", error);
      message.error("Logout failed!");
    }
  };

  const handleMenuClick = ({ key }) => {
    if (key === "5") {
      logoutMutation(); // Call the logout function when Logout is clicked
    } else if (key === "link") {
      // Do nothing
    } else if (key === "0") {
      navigate("/WBM/");
    } else {
      navigate("/WBM/" + key);
    }
  };

  return (
    <>
      <div style={{ height: "100%", display: "flex", flexDirection: "column" }}>
        <Menu
          onClick={handleMenuClick}
          style={{
            width: 180,
            height: "100vh",
          }}
          defaultSelectedKeys={[]}
          defaultOpenKeys={[]}
          mode={mode}
          theme={theme}
          items={items}
        />
      </div>
    </>
  );
}
