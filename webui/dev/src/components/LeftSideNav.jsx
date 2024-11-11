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
  MenuFoldOutlined,
  MenuUnfoldOutlined,
} from "@ant-design/icons";
import { Button, Menu, message, ConfigProvider } from "antd";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../utils/auth";

// Function to get menu items
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
    getItem("PLC4X", "13", null, null, true),
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
  const [collapsed, setCollapsed] = useState(false);
  const auth = useAuth();
  const navigate = useNavigate();

  const toggleCollapsed = () => {
    setCollapsed(!collapsed);
  };

  const logoutMutation = async () => {
    const endpoint = localStorage.getItem("serverEndpoint") || "localhost";
    const serverURL = `http://${endpoint}:9999`;
    const mutation = `mutation {
      deleteAccessToken(token: "${auth.token.replace(/["']/g, "")}") {
        ok
        code
        message
      }
    }`;

    try {
      const response = await fetch(`${serverURL}/admin/api`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ query: mutation }),
      });

      const data = await response.json();

      if (data.errors) {
        message.error(data.errors[0].message);
      } else {
        message.success("Logout successful!");
        auth.logout();
        navigate("/");
      }
    } catch (error) {
      console.error("Error sending logout mutation:", error);
      message.error("Logout failed!");
    }
  };

  // Handle menu click events
  const handleMenuClick = ({ key }) => {
    if (key === "5") {
      logoutMutation();
    } else if (key === "link") {
      // Do nothing
    } else if (key === "0") {
      navigate("/WBM/");
    } else {
      navigate("/WBM/" + key);
    }
  };

  return (
    <div style={{ height: "100%", display: "flex", flexDirection: "column" }}>
      {/* <Button
        type="submit"
        onClick={toggleCollapsed}
        style={{ marginBottom: 16 }}
      >
        {collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
      </Button> */}
      <ConfigProvider
        theme={{
          components: {
            Menu: {
              itemHoverBg: "#b7f7ca",
              itemSelectedColor: "#0fab3e",
              itemSelectedBg: "#d9ffe4",
            },
          },
        }}
      >
        <Menu
          onClick={handleMenuClick}
          style={{
            width: 180,
            height: "100vh",
          }}
          defaultSelectedKeys={["0"]}
          defaultOpenKeys={[]}
          mode="inline"
          theme="light"
          inlineCollapsed={collapsed}
          items={items}
        />
      </ConfigProvider>
    </div>
  );
}
